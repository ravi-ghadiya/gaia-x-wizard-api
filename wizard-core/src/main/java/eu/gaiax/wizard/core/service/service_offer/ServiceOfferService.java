package eu.gaiax.wizard.core.service.service_offer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.smartsensesolutions.java.commons.FilterRequest;
import com.smartsensesolutions.java.commons.base.repository.BaseRepository;
import com.smartsensesolutions.java.commons.base.service.BaseService;
import com.smartsensesolutions.java.commons.filter.FilterCriteria;
import com.smartsensesolutions.java.commons.operator.Operator;
import com.smartsensesolutions.java.commons.specification.SpecificationUtil;
import eu.gaiax.wizard.api.client.MessagingQueueClient;
import eu.gaiax.wizard.api.exception.BadDataException;
import eu.gaiax.wizard.api.exception.EntityNotFoundException;
import eu.gaiax.wizard.api.model.*;
import eu.gaiax.wizard.api.model.did.ServiceEndpointConfig;
import eu.gaiax.wizard.api.model.policy.ServiceOfferPolicyDto;
import eu.gaiax.wizard.api.model.policy.SubdivisionName;
import eu.gaiax.wizard.api.model.request.ParticipantValidatorRequest;
import eu.gaiax.wizard.api.model.service_offer.*;
import eu.gaiax.wizard.api.utils.StringPool;
import eu.gaiax.wizard.api.utils.Validate;
import eu.gaiax.wizard.core.service.credential.CredentialService;
import eu.gaiax.wizard.core.service.data_master.StandardTypeMasterService;
import eu.gaiax.wizard.core.service.data_master.SubdivisionCodeMasterService;
import eu.gaiax.wizard.core.service.hashing.HashingService;
import eu.gaiax.wizard.core.service.participant.InvokeService;
import eu.gaiax.wizard.core.service.participant.ParticipantService;
import eu.gaiax.wizard.core.service.signer.SignerService;
import eu.gaiax.wizard.core.service.ssl.CertificateService;
import eu.gaiax.wizard.dao.entity.Credential;
import eu.gaiax.wizard.dao.entity.data_master.StandardTypeMaster;
import eu.gaiax.wizard.dao.entity.participant.Participant;
import eu.gaiax.wizard.dao.entity.service_offer.ServiceOffer;
import eu.gaiax.wizard.dao.repository.participant.ParticipantRepository;
import eu.gaiax.wizard.dao.repository.service_offer.ServiceOfferRepository;
import eu.gaiax.wizard.vault.Vault;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.*;
import java.util.stream.StreamSupport;

import static eu.gaiax.wizard.api.utils.StringPool.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ServiceOfferService extends BaseService<ServiceOffer, UUID> {

    private final CredentialService credentialService;
    private final ServiceOfferRepository serviceOfferRepository;
    private final ObjectMapper objectMapper;
    private final ParticipantRepository participantRepository;
    private final ParticipantService participantService;
    private final SignerService signerService;
    private final PolicyService policyService;
    private final SpecificationUtil<ServiceOffer> serviceOfferSpecificationUtil;
    private final ServiceEndpointConfig serviceEndpointConfig;
    private final StandardTypeMasterService standardTypeMasterService;
    private final ServiceLabelLevelService labelLevelService;
    private final Vault vault;
    private final CertificateService certificateService;
    private final MessagingQueueClient messagingQueueClient;
    private final SubdivisionCodeMasterService subdivisionCodeMasterService;
    private final Random random = new Random();

    @Value("${wizard.host.wizard}")
    private String wizardHost;

    @Transactional(isolation = Isolation.READ_UNCOMMITTED, propagation = Propagation.REQUIRED)
    public ServiceOfferResponse createServiceOffering(CreateServiceOfferingRequest request, String id, boolean isOwnDid) throws IOException {
        this.validateServiceOfferMainRequest(request);

        Participant participant;
        if (id != null) {
            participant = this.participantRepository.findById(UUID.fromString(id)).orElse(null);
            Validate.isNull(participant).launch(new BadDataException("participant.not.found"));
            Credential participantCred = this.credentialService.getByParticipantWithCredentialType(participant.getId(), CredentialTypeEnum.LEGAL_PARTICIPANT.getCredentialType());
            this.signerService.validateRequestUrl(Collections.singletonList(participantCred.getVcUrl()), "participant.url.not.found", null);
            request.setParticipantJsonUrl(participantCred.getVcUrl());
        } else {
            ParticipantValidatorRequest participantValidatorRequest = new ParticipantValidatorRequest(request.getParticipantJsonUrl(), request.getVerificationMethod(), request.getPrivateKey(), false, isOwnDid);
            participant = this.participantService.validateParticipant(participantValidatorRequest);
            Validate.isNull(participant).launch(new BadDataException("participant.not.found"));
        }

        if (participant.isKeyStored()) {
            this.addParticipantPrivateKey(participant.getId().toString(), participant.getDid(), request);
        }

        if (request.isStoreVault() && !participant.isKeyStored()) {
            this.certificateService.uploadCertificatesToVault(participant.getId().toString(), null, null, null, request.getPrivateKey());
            participant.setKeyStored(true);
            this.participantRepository.save(participant);
        }

        String serviceName = "service_" + this.getRandomString();
        String serviceHostUrl = this.wizardHost + participant.getId() + "/" + serviceName + ".json";

        Map<String, Object> credentialSubject = request.getCredentialSubject();
        if (request.getCredentialSubject().containsKey(GX_POLICY)) {
            this.generateServiceOfferPolicy(participant, serviceName, serviceHostUrl, credentialSubject);
        }

        this.createTermsConditionHash(credentialSubject);
        request.setCredentialSubject(credentialSubject);

        Map<String, String> complianceCredential = this.signerService.signService(participant, request, serviceName);
        if (!participant.isOwnDidSolution()) {
            this.signerService.addServiceEndpoint(participant.getId(), serviceHostUrl, this.serviceEndpointConfig.linkDomainType(), serviceHostUrl);
        }

        Credential serviceOffVc = this.credentialService.createCredential(complianceCredential.get(SERVICE_VC), serviceHostUrl, CredentialTypeEnum.SERVICE_OFFER.getCredentialType(), "", participant);
        List<StandardTypeMaster> supportedStandardList = this.getSupportedStandardList(complianceCredential.get(SERVICE_VC));

        ServiceOffer serviceOffer = ServiceOffer.builder()
                .name(request.getName())
                .participant(participant)
                .credential(serviceOffVc)
                .serviceOfferStandardType(supportedStandardList)
                .description(request.getDescription() == null ? "" : request.getDescription())
                .veracityData(complianceCredential.getOrDefault(TRUST_INDEX, null))
                .build();

        this.addLabelLevel(participant, request, serviceOffer, serviceHostUrl);

        serviceOffer = this.serviceOfferRepository.save(serviceOffer);
        this.publishServiceComplianceToMessagingQueue(serviceOffer.getId(), complianceCredential.get(SERVICE_VC));

        TypeReference<List<Map<String, Object>>> typeReference = new TypeReference<>() {
        };
        this.objectMapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
        List<Map<String, Object>> vc = this.objectMapper.readValue(serviceOffer.getCredential().getVcJson(), typeReference);
        return ServiceOfferResponse.builder()
                .vcUrl(serviceOffer.getCredential().getVcUrl())
                .name(serviceOffer.getName())
                .veracityData(serviceOffer.getVeracityData())
                .vcJson(vc)
                .description(serviceOffer.getDescription())
                .build();
    }

    private void addLabelLevel(Participant participant, CreateServiceOfferingRequest request, ServiceOffer serviceOffer, String serviceHostUrl) throws JsonProcessingException {
        // todo sign label level vc
        Map<String, String> labelLevelVc = new HashMap<>();

        if (request.getCredentialSubject().containsKey(GX_CRITERIA)) {
            LabelLevelRequest labelLevelRequest = new LabelLevelRequest(this.objectMapper.convertValue(request.getCredentialSubject().get(GX_CRITERIA), Map.class), request.getPrivateKey(), request.getParticipantJsonUrl(), request.getVerificationMethod(), request.isStoreVault());
            labelLevelVc = this.labelLevelService.createLabelLevelVc(labelLevelRequest, participant, serviceHostUrl);
            request.getCredentialSubject().remove(GX_CRITERIA);
            if (labelLevelVc != null) {
                request.getCredentialSubject().put(GX_LABEL_LEVEL, labelLevelVc.get("vcUrl"));
            }
        }

        if (Objects.requireNonNull(labelLevelVc).containsKey(LABEL_LEVEL_VC)) {
            JsonNode descriptionCredential = this.objectMapper.readTree(labelLevelVc.get(LABEL_LEVEL_VC)).path(CREDENTIAL_SUBJECT);
            if (descriptionCredential != null) {
                serviceOffer.setLabelLevel(descriptionCredential.path(GX_LABEL_LEVEL).asText());
            }
        }

        if (!CollectionUtils.isEmpty(labelLevelVc)) {
            this.labelLevelService.saveServiceLabelLevelLink(labelLevelVc.get(LABEL_LEVEL_VC), labelLevelVc.get("vcUrl"), participant, serviceOffer);
        }
    }

    private void addParticipantPrivateKey(String participantId, String did, CreateServiceOfferingRequest request) {
        if (!this.vault.get(participantId).containsKey("pkcs8.key")) {
            throw new BadDataException("private.key.not.found");
        }
        request.setPrivateKey(this.vault.get(participantId).get("pkcs8.key").toString());
        request.setVerificationMethod(did);
    }

    private void generateServiceOfferPolicy(Participant participant, String serviceName, String serviceHostUrl, Map<String, Object> credentialSubject) throws JsonProcessingException {
        String policyId = participant.getId() + "/" + serviceName + "_policy";
        String policyUrl = this.wizardHost + policyId + ".json";
        ServiceOfferPolicyDto policy = this.objectMapper.convertValue(credentialSubject.get(GX_POLICY), ServiceOfferPolicyDto.class);
        ODRLPolicyRequest odrlPolicyRequest = new ODRLPolicyRequest(policy.location(), StringPool.POLICY_LOCATION_LEFT_OPERAND, serviceHostUrl, participant.getDid(), this.wizardHost, serviceName);

        String hostPolicyJson = this.objectMapper.writeValueAsString(this.policyService.createServiceOfferPolicy(odrlPolicyRequest, policyUrl));
        if (StringUtils.hasText(hostPolicyJson)) {
            this.policyService.hostPolicy(hostPolicyJson, policyId);
            if (StringUtils.hasText(policy.customAttribute())) {
                credentialSubject.put(GX_POLICY, List.of(policyUrl, policy.customAttribute()));
            } else {
                credentialSubject.put(GX_POLICY, List.of(policyUrl));
            }
            this.credentialService.createCredential(hostPolicyJson, policyUrl, CredentialTypeEnum.ODRL_POLICY.getCredentialType(), "", participant);
        }
    }

    private void publishServiceComplianceToMessagingQueue(UUID serviceOfferId, String complianceCredential) throws JsonProcessingException {
        PublishToQueueRequest publishToQueueRequest = new PublishToQueueRequest();
        publishToQueueRequest.setSource(this.wizardHost);
        publishToQueueRequest.setData((Map<String, Object>) this.objectMapper.readValue(complianceCredential, Map.class).get("complianceCredential"));

        try {
            ResponseEntity<Object> publishServiceComplianceResponse = this.messagingQueueClient.publishServiceCompliance(publishToQueueRequest);
            if (publishServiceComplianceResponse.getStatusCode().equals(HttpStatus.CREATED)) {
                log.info("Publish Service Response Headers Set: {}", publishServiceComplianceResponse.getHeaders().keySet());
                String rawMessageId = publishServiceComplianceResponse.getHeaders().get("location").get(0);
                String messageReferenceId = rawMessageId.substring(rawMessageId.lastIndexOf("/") + 1);

                this.serviceOfferRepository.updateMessageReferenceId(serviceOfferId, messageReferenceId);
            }
        } catch (Exception e) {
            log.error("Error encountered while publishing service to message queue", e);
        }
    }

    @SneakyThrows
    private List<StandardTypeMaster> getSupportedStandardList(String serviceJsonString) {
        JsonNode serviceOfferingJsonNode = this.getServiceCredentialSubject(serviceJsonString);
        assert serviceOfferingJsonNode != null;

        if (!serviceOfferingJsonNode.has(StringPool.GX_DATA_PROTECTION_REGIME)) {
            return Collections.emptyList();
        }

        if (serviceOfferingJsonNode.get(StringPool.GX_DATA_PROTECTION_REGIME).isValueNode()) {
            String dataProtectionRegime = serviceOfferingJsonNode.get(StringPool.GX_DATA_PROTECTION_REGIME).asText();
            return this.standardTypeMasterService.findAllByTypeIn(List.of(dataProtectionRegime));
        } else {
            ObjectReader reader = this.objectMapper.readerFor(new TypeReference<List<String>>() {
            });

            List<String> standardNameList = reader.readValue(serviceOfferingJsonNode.get(StringPool.GX_DATA_PROTECTION_REGIME));
            return this.standardTypeMasterService.findAllByTypeIn(standardNameList);
        }
    }

    @SneakyThrows
    private JsonNode getServiceCredentialSubject(String serviceJsonString) {
        JsonNode serviceOffer = this.objectMapper.readTree(serviceJsonString);
        JsonNode verifiableCredential = serviceOffer.get(SELF_DESCRIPTION_CREDENTIAL).get(VERIFIABLE_CREDENTIAL_CAMEL_CASE);

        for (JsonNode credential : verifiableCredential) {
            if (credential.get(CREDENTIAL_SUBJECT).get(TYPE).asText().equals(GX_SERVICE_OFFERING)) {
                return credential.get(CREDENTIAL_SUBJECT);
            }
        }
        return null;
    }

    private String getRandomString() {
        final String possibleCharacters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

        StringBuilder randomString = new StringBuilder(5);
        for (int i = 0; i < 4; i++) {
            int randomIndex = this.random.nextInt(possibleCharacters.length());
            char randomChar = possibleCharacters.charAt(randomIndex);
            randomString.append(randomChar);
        }
        return randomString.toString();
    }

    private void createTermsConditionHash(Map<String, Object> credentialSubject) throws IOException {
        if (credentialSubject.containsKey(GX_TERMS_AND_CONDITIONS)) {
            Map<String, Object> termsAndConditions = this.objectMapper.convertValue(credentialSubject.get(GX_TERMS_AND_CONDITIONS), Map.class);
            if (termsAndConditions.containsKey(GX_URL_CAPS)) {
                String content = HashingService.fetchJsonContent(termsAndConditions.get(GX_URL_CAPS).toString());
                termsAndConditions.put(GX_HASH, HashingService.generateSha256Hash(content));
                credentialSubject.put(GX_TERMS_AND_CONDITIONS, termsAndConditions);
            }
        }
    }

    public void validateServiceOfferRequest(CreateServiceOfferingRequest request) {
        Validate.isFalse(StringUtils.hasText(request.getName())).launch("invalid.service.name");
        Validate.isTrue(CollectionUtils.isEmpty(request.getCredentialSubject())).launch("invalid.credential");
    }

    public List<String> getLocationFromService(ServiceIdRequest serviceIdRequest) {
        String[] subdivisionCodeArray = this.policyService.getLocationByServiceOfferingId(serviceIdRequest.id());
        if (subdivisionCodeArray.length > 0) {
            List<SubdivisionName> subdivisionNameList = this.subdivisionCodeMasterService.getNameListBySubdivisionCode(subdivisionCodeArray);
            if (!CollectionUtils.isEmpty(subdivisionNameList)) {
                return subdivisionNameList.stream().map(SubdivisionName::name).toList();
            }
        }

        return Collections.emptyList();
    }

    @Override
    protected BaseRepository<ServiceOffer, UUID> getRepository() {
        return this.serviceOfferRepository;
    }

    @Override
    protected SpecificationUtil<ServiceOffer> getSpecificationUtil() {
        return this.serviceOfferSpecificationUtil;
    }

    public void validateServiceOfferMainRequest(CreateServiceOfferingRequest request) throws JsonProcessingException {
        this.validateCredentialSubject(request);
        this.validateAggregationOf(request);
        this.validateDependsOn(request);
        this.validateDataAccountExport(request);
        if (!request.getCredentialSubject().containsKey(GX_POLICY)) {
            throw new BadDataException("invalid.policy");
        }
    }

    private void validateCredentialSubject(CreateServiceOfferingRequest request) {
        if (CollectionUtils.isEmpty(request.getCredentialSubject())) {
            throw new BadDataException("invalid.credential");
        }
    }

    private void validateAggregationOf(CreateServiceOfferingRequest request) throws JsonProcessingException {
        if (!request.getCredentialSubject().containsKey(AGGREGATION_OF) || !StringUtils.hasText(request.getCredentialSubject().get(AGGREGATION_OF).toString())) {
            throw new BadDataException("aggregation.of.not.found");
        }
        JsonNode jsonNode = this.objectMapper.readTree(this.objectMapper.writeValueAsString(request.getCredentialSubject()));

        JsonNode aggregationOfArray = jsonNode.at("/gx:aggregationOf");

        List<String> ids = new ArrayList<>();
        aggregationOfArray.forEach(item -> {
            if (item.has(ID)) {
                String id = item.get(ID).asText();
                ids.add(id);
            }
        });
        this.signerService.validateRequestUrl(ids, "aggregation.of.not.found", Collections.singletonList("holderSignature"));
    }

    private void validateDependsOn(CreateServiceOfferingRequest request) throws JsonProcessingException {
        if (request.getCredentialSubject().get(DEPENDS_ON) != null) {
            JsonNode jsonNode = this.objectMapper.readTree(this.objectMapper.writeValueAsString(request.getCredentialSubject()));

            JsonNode aggregationOfArray = jsonNode.at("/gx:dependsOn");

            List<String> ids = new ArrayList<>();
            aggregationOfArray.forEach(item -> {
                if (item.has(ID)) {
                    String id = item.get(ID).asText();
                    ids.add(id);
                }
            });
            this.signerService.validateRequestUrl(ids, "depends.on.not.found", null);
        }

    }

    private void validateDataAccountExport(CreateServiceOfferingRequest request) {
        Map<String, Object> credentialSubject = request.getCredentialSubject();
        if (!credentialSubject.containsKey(GX_DATA_ACCOUNT_EXPORT)) {
            throw new BadDataException("data.account.export.not.found");
        }

        TypeReference<Map<String, Object>> typeReference = new TypeReference<>() {
        };
        this.objectMapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);

        Map<String, Object> export = this.objectMapper.convertValue(credentialSubject.get(GX_DATA_ACCOUNT_EXPORT), typeReference);

        this.validateExportField(export, "gx:requestType", "requestType.of.not.found");
        this.validateExportField(export, "gx:accessType", "accessType.of.not.found");
        this.validateExportField(export, "gx:formatType", "formatType.of.not.found");
    }

    private void validateExportField(Map<String, Object> export, String fieldName, String errorMessage) {
        if (!export.containsKey(fieldName) || !StringUtils.hasText(export.get(fieldName).toString())) {
            throw new BadDataException(errorMessage);
        }
    }

    public PageResponse<ServiceFilterResponse> filterServiceOffering(FilterRequest filterRequest, String participantId) {

        if (StringUtils.hasText(participantId)) {
            FilterCriteria participantCriteria = new FilterCriteria(StringPool.FILTER_PARTICIPANT_ID, Operator.CONTAIN, Collections.singletonList(participantId));
            List<FilterCriteria> filterCriteriaList = filterRequest.getCriteria() != null ? filterRequest.getCriteria() : new ArrayList<>();
            filterCriteriaList.add(participantCriteria);
            filterRequest.setCriteria(filterCriteriaList);
        }

        Page<ServiceOffer> serviceOfferPage = this.filter(filterRequest);
        List<ServiceFilterResponse> resourceList = this.objectMapper.convertValue(serviceOfferPage.getContent(), new TypeReference<>() {
        });

        return PageResponse.of(resourceList, serviceOfferPage, filterRequest.getSort());
    }

    @SneakyThrows
    public ServiceDetailResponse getServiceOfferingById(UUID serviceOfferId) {
        ServiceOffer serviceOffer = this.serviceOfferRepository.findById(serviceOfferId).orElse(null);
        Validate.isNull(serviceOffer).launch(new EntityNotFoundException("service.offer.not.found"));

        ServiceDetailResponse serviceDetailResponse = this.objectMapper.convertValue(serviceOffer, ServiceDetailResponse.class);
        JsonNode veracityData = this.objectMapper.readTree(serviceOffer.getVeracityData());
        serviceDetailResponse.setTrustIndex(veracityData.get(TRUST_INDEX).asDouble());

        String serviceOfferJsonString = InvokeService.executeRequest(serviceOffer.getVcUrl(), HttpMethod.GET);
        JsonNode serviceOfferJson = new ObjectMapper().readTree(serviceOfferJsonString);
        ArrayNode verifiableCredentialList = (ArrayNode) serviceOfferJson.get(SELF_DESCRIPTION_CREDENTIAL).get(VERIFIABLE_CREDENTIAL_CAMEL_CASE);
        JsonNode serviceOfferCredentialSubject = this.getServiceOfferCredentialSubject(verifiableCredentialList);

        serviceDetailResponse.setDataAccountExport(this.getDataAccountExportDto(serviceOfferCredentialSubject));
        serviceDetailResponse.setTnCUrl(serviceOfferCredentialSubject.get(GX_TERMS_AND_CONDITIONS).get(GX_URL_CAPS).asText());
        serviceDetailResponse.setProtectionRegime(this.objectMapper.convertValue(serviceOfferCredentialSubject.get(GX_DATA_PROTECTION_REGIME), new TypeReference<>() {
        }));

        serviceDetailResponse.setLocations(Set.of(this.policyService.getLocationByServiceOfferingId(serviceDetailResponse.getCredential().getVcUrl())));

        if (serviceOfferCredentialSubject.has(AGGREGATION_OF)) {
            serviceDetailResponse.setResources(this.getAggregationOrDependentDtoSet((ArrayNode) serviceOfferCredentialSubject.get(AGGREGATION_OF), false));
        }

        if (serviceOfferCredentialSubject.has(DEPENDS_ON)) {
            serviceDetailResponse.setDependedServices(this.getAggregationOrDependentDtoSet((ArrayNode) serviceOfferCredentialSubject.get(DEPENDS_ON), true));
        }

        return serviceDetailResponse;
    }

    private DataAccountExportDto getDataAccountExportDto(JsonNode credentialSubject) {
        return DataAccountExportDto.builder()
                .requestType(credentialSubject.get(GX_DATA_ACCOUNT_EXPORT).get(GX_REQUEST_TYPE).asText())
                .accessType(credentialSubject.get(GX_DATA_ACCOUNT_EXPORT).get(GX_ACCESS_TYPE).asText())
                .formatType(this.getFormatSet(credentialSubject.get(GX_DATA_ACCOUNT_EXPORT).get(GX_FORMAT_TYPE)))
                .build();
    }

    private Set<String> getFormatSet(JsonNode formatTypeNode) {
        return formatTypeNode.isArray() ? this.objectMapper.convertValue(formatTypeNode, new TypeReference<>() {
        }) : Collections.singleton(formatTypeNode.asText());
    }

    private JsonNode getServiceOfferCredentialSubject(JsonNode credentialSubjectList) {
        for (JsonNode credential : credentialSubjectList) {
            if (credential.get(StringPool.CREDENTIAL_SUBJECT).get(TYPE).asText().equals(GX_SERVICE_OFFERING)) {
                return credential.get(StringPool.CREDENTIAL_SUBJECT);
            }
        }

        return null;
    }

    private JsonNode getResourceCredentialSubject(JsonNode credentialSubjectList) {
        for (JsonNode credential : credentialSubjectList) {
            if (ResourceType.getValueSet().contains(credential.get(StringPool.CREDENTIAL_SUBJECT).get(TYPE).asText())) {
                return credential.get(StringPool.CREDENTIAL_SUBJECT);
            }
        }

        return null;
    }


    private Set<AggregateAndDependantDto> getAggregationOrDependentDtoSet(ArrayNode aggregationOrDependentArrayNode, boolean isService) {
        Set<AggregateAndDependantDto> aggregateAndDependantDtoSet = new HashSet<>();

        StreamSupport.stream(aggregationOrDependentArrayNode.spliterator(), true).forEach(node -> {
            AggregateAndDependantDto aggregateAndDependantDto = new AggregateAndDependantDto();
            aggregateAndDependantDto.setCredentialSubjectId(node.get(ID).asText());

            String serviceOrResourceJsonString = InvokeService.executeRequest(node.get(ID).asText(), HttpMethod.GET);
            JsonNode serviceOrResourceJson;
            try {
                serviceOrResourceJson = new ObjectMapper().readTree(serviceOrResourceJsonString);
                ArrayNode verifiableCredentialList = (ArrayNode) serviceOrResourceJson.get(SELF_DESCRIPTION_CREDENTIAL).get(VERIFIABLE_CREDENTIAL_CAMEL_CASE);

                JsonNode serviceOfferOrResourceCredentialSubject;
                if (isService) {
                    serviceOfferOrResourceCredentialSubject = this.getServiceOfferCredentialSubject(verifiableCredentialList);
                } else {
                    serviceOfferOrResourceCredentialSubject = this.getResourceCredentialSubject(verifiableCredentialList);
                }
                aggregateAndDependantDto.setName(serviceOfferOrResourceCredentialSubject.get(NAME).asText());

            } catch (JsonProcessingException e) {
                log.error("Error while parsing JSON. url: " + node.get(ID).asText());
            }

            aggregateAndDependantDtoSet.add(aggregateAndDependantDto);

        });

        return aggregateAndDependantDtoSet;
    }
}

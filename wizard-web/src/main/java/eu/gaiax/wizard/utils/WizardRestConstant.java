package eu.gaiax.wizard.utils;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class WizardRestConstant {
    public static final String GET_ENTERPRISE_FILES = "/.well-known/{fileName}";
    public static final String REGISTER = "/register";
    public static final String ENTERPRISE = "/enterprises";//ER
    public static final String ENTERPRISE_LIST = ENTERPRISE + "/list";//AR
    public static final String ENTERPRISE_BY_ID = ENTERPRISE + "/{id}";//AR
    public static final String CREATE_SUBDOMAIN = "/subdomain/{enterpriseId}";
    public static final String CREATE_CERTIFICATE = "/certificate/{enterpriseId}";
    public static final String CREATE_INGRESS = "/ingress/{enterpriseId}";
    public static final String CREATE_DID = "/did/{enterpriseId}";
    public static final String CREATE_PARTICIPANT_JSON = "/participant/{enterpriseId}";
    public static final String ENTERPRISE_VC = ENTERPRISE + "/vcs";//ER
    public static final String SERVICE_OFFERING = ENTERPRISE + "/service-offers";//ER
    public static final String SERVICE_OFFER_BY_ID = ENTERPRISE + "/service-offers/{id}";//ER
    public static final String CATALOGUE = "/catalogue";//ER
    public static final String CREATE_VP = ENTERPRISE + "/vc/{name}/vp";//ER
    public static final String SERVICE_OFFER_DETAILS = ENTERPRISE + "/service-offers/{offerId}/details";//ER
    public static final String EXPORT_KEYS = ENTERPRISE + "/keys/export"; //ER
}

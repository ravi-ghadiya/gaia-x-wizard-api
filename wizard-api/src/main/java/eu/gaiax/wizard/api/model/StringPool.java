/*
 * Copyright (c) 2023 | smartSense
 */

package eu.gaiax.wizard.api.model;

/**
 * The interface String pool.
 */
public class StringPool {
    /**
     * The constant JOB_TYPE_CREATE_SUB_DOMAIN.
     */
    public static final String JOB_TYPE_CREATE_SUB_DOMAIN = "JOB_TYPE_CREATE_SUB_DOMAIN";

    /**
     * The constant JOB_TYPE_CREATE_CERTIFICATE.
     */
    public static final String JOB_TYPE_CREATE_CERTIFICATE = "JOB_TYPE_CREATE_CERTIFICATE";

    /**
     * The constant JOB_TYPE_CREATE_INGRESS.
     */
    public static final String JOB_TYPE_CREATE_INGRESS = "JOB_TYPE_CREATE_INGRESS";

    /**
     * The constant JOB_TYPE_CREATE_DID.
     */
    public static final String JOB_TYPE_CREATE_DID = "JOB_TYPE_CREATE_DID";

    /**
     * The constant JOB_TYPE_CREATE_PARTICIPANT.
     */
    public static final String JOB_TYPE_CREATE_PARTICIPANT = "JOB_TYPE_CREATE_PARTICIPANT";

    /**
     * The constant ENTERPRISE_ID.
     */
    public static final String ENTERPRISE_ID = "enterpriseId";
    /**
     * The constant JOB_TYPE.
     */
    public static final String JOB_TYPE = "JOB_TYPE";
    /**
     * The constant S3_BUCKET_NAME.
     */
    public static final String S3_BUCKET_NAME = "smartsense-gaiax-mvp";

    /**
     * The constant ROLE.
     */
    public static final String ROLE = "role";
    /**
     * The constant EMAIL.
     */
    public static final String EMAIL = "email";

    /**
     * The constant INVALID_USERNAME_OR_PASSWORD.
     */
    public static final String INVALID_USERNAME_OR_PASSWORD = "invalid.username.or.password"; //pragma: allowlist secret

    /**
     * The constant TERMS_AND_CONDITIONS_HASH.
     */
    public static final String TERMS_AND_CONDITIONS_HASH = "hash_value"; //pragma: allowlist secret

    public static final String REQUIRED_ACTIONS_PATH = "/login-actions/action-token?key=";

    private StringPool() {
    }
}

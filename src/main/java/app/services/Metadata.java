package app.services;

public class Metadata {
    public static final String NAMESPACE = "acme.itzg.github.io";

    public static final String ROLE_LABEL = NAMESPACE + "/role";
    public static final String SOLVER_ROLE = "solver";

    public static final String HOST_ANNOTATION = NAMESPACE + "/host";

    public static final String ISSUER_LABEL = NAMESPACE + "/issuer";

    public static final String IDENTIFIERS_ANNOTATION = NAMESPACE + "/hosts";

    public static final String SOLVER_SERVICE_PORT_NAME = "http";

    private Metadata() {}
}

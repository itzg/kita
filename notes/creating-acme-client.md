- [ACME Protocol Updates from Let's Encrypt](https://letsencrypt.org/docs/acme-protocol-updates/)
- [RFC 8555](https://datatracker.ietf.org/doc/html/rfc8555)
- [Access directory URLs](https://datatracker.ietf.org/doc/html/rfc8555#section-7.1.1)

## Implementation Notes

- [Kubernetes client with Fabric8](https://github.com/fabric8io/kubernetes-client)
- [JWS etc with Nimbus JOSE](https://connect2id.com/products/nimbus-jose-jwt)

## Issuing sequence



```mermaid
flowchart TD
    submitOrder(Submit order)
    loadAuthz(Load authorizations)
    forEachAuth{{For each authorization}}
    prepareChallenge(Prepare challenge controller)
    setupSolverIngress(Setup solver ingress)
    requestChallenge(Request validation challenge)
    receivesValidation(Receives validation challenge)
    pollAuthResourceForFinalized(Poll auth resource)
    isAuthFinalized{Auth finalized?}
    submitCsr(Submit CSR to finalize URL)
    retrieveCertChain(Retrieve cert chain)
    storeTlsSecret(Store TLS secret)
    nextAuth(( ))
    
    submitOrder --> loadAuthz
    loadAuthz --> forEachAuth
    forEachAuth --> prepareChallenge
    prepareChallenge --> setupSolverIngress
    setupSolverIngress --> requestChallenge
    requestChallenge --> receivesValidation
    receivesValidation --> pollAuthResourceForFinalized
    pollAuthResourceForFinalized --> isAuthFinalized
    isAuthFinalized -- no --> pollAuthResourceForFinalized
    isAuthFinalized -- yes --> nextAuth
    nextAuth--> forEachAuth
    nextAuth --> submitCsr
    submitCsr --> retrieveCertChain
    retrieveCertChain --> storeTlsSecret

```
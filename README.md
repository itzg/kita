An easy-to-deploy and easy-to-use ACME client service for Kubernetes Ingress instances.

# Kubernetes Ingress TLS using ACME

> **Why not cert-manager?**
> 
> Keeping my cert-manager configuration correct was an ongoing struggle. Ultimately, I ran into an issue where cert-manager didn't set the ingressClassName correctly on the solver, no matter what I told it. I had a cert that was about to expire within a week...so I wrote this application.

## Installation

### Configuration

Apply the `kita` config map by applying the starter config, replacing `SET_TO_TRUE` with `true` and `CONFIGURE_EMAIL` with your email address:

```shell
kubectl create --edit -f https://raw.githubusercontent.com/itzg/kita/main/config-starter.yml
```

### Install

```shell
kubectl apply -f https://raw.githubusercontent.com/itzg/kita/main/install.yml
```

## Upgrading

If the kita deployment's image is the default `latest`, then restarting the deployment will pick up the newest image:

```shell
kubectl rollout restart deployment/kita
```

Otherwise, change the image tag on the deployment and re-apply.

## Usage

Add the label `acme.itzg.github.io/issuer` to your ingresses with its value set to one `kita.issuers` keys in the config map created above.

For example:

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  labels:
    acme.itzg.github.io/issuer: letsencrypt-prod
  name: app
spec:
  ingressClassName: public
  rules:
  - host: app.example.com
    http:
      paths:
      - backend:
          service:
            name: app
            port:
              name: http
        path: /
        pathType: Prefix
  tls:
  - hosts:
    - app.example.com
    secretName: app-tls
```
## Project Prerequisites

Your project must meet the following requirements before on-boarding into Openshift.

- [ ] Is a Java Application
- [ ] Is `Maven` based
- [ ] Must produce a `JAR` artifact
- [ ] Contains `./ocp/config.yml` at project root
- [ ] Is a [12-Factor](https://12factor.net/) Application


### Configurations of `config.yml`

All project-specific configurations must be contained in a file at  `./ocp/config.yml`, which at minimum, must contain the following.

##### Required
```yaml
readinessProbe: "<readliness probe URL - e.g. /actuator/health>"
livelinessProbe: "<liveliness probe URL - e.g. /actuator/health>"
secretKeyRef: "<name of Secret>"
configMapRef: "<name of ConfigMap>"
```

##### Optional
It is preferable to stick with project conventions, but overriding default values is possible for the following fields.
```yaml
replicas: <number of pods> - If not specified, the current number of replicas will be kept.  If the current number is 0, the deployment will be automatically scaled to 1.
```

### Folder Structure for `./ocp`
The `./ocp` directory structure contains sub-directories for each environment.  The Pipeline, as it currently stands, looks for ConfigMaps and Secrets in the "dev" sub-directory.  As the pipeline expands to include QA and PROD deployments, it will look into its respective sub-directories.   The `config.yml` at the root can be seen as a "general" configuration that applies to all environments.  The ability to add environmental overrides will be added in a future release.

```bash
ocp/
├── config.yml
├── dev
├── prod
└── qa
```


### Config Maps
The Openshift `ConfigMap` is the preferred way of injecting environment-specific settings into your application.  The pipeline currently only accepts one `ConfigMap` per application.  The `configMapRef` specified in the `config.yml` will be wired into the `DeploymentConfig`, via the `oc set env` command.

### Secrets
The Openshift `Secret` is processed the same way as the `ConfigMap`.  They are loaded into Environment variables in the running containers.

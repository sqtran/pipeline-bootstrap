package com.steve.ocp

import com.steve.ocp.util.FileLoader

def process(def params) {

	def dev_image_tag = "${params.projectName}:${params.selectedImageTag}"
	def qa_image_tag = "${dev_image_tag}-rc-b${currentBuild.number}"

	openshift.withCluster() {
      stage('Push into Artifactory') {
        configFileProvider([configFile(fileId: 'docker_config.json', targetLocation: "/home/jenkins/.docker/config.json")]) {
          // the ARTIFACTORY_URL also includes the path to store the image in Artifactory

          def results = openshift.raw("image mirror $OCPDEV_REGISTRY_URL/${params.ocpnamespace}/$dev_image_tag $ARTIFACTORY_URL/$qa_image_tag --insecure ")
          echo "image mirror results = $results"
        }
      }

      stage('Get Configs from SCM') {
				// the image has a reference to the git commit's SHA
        def image_info = openshift.raw("image info $ARTIFACTORY_URL/$qa_image_tag --insecure")
				def commitHash = (image_info =~ /GIT_REF=[a-z0-9]+/)[0].split("=")[1] ?: ""

				if(commitHash != "") {
					// not good, but necessary until we fix our self-signed certificate issue
					try {
						checkout([$class: 'GitSCM', branches: [[name: commitHash ]], userRemoteConfigs: [[credentialsId: 'git-sa', url: params.gitUrl]]])
					} catch (Exception e) {
						println e
						sh "git config http.sslVerify false"
						checkout([$class: 'GitSCM', branches: [[name: commitHash ]], userRemoteConfigs: [[credentialsId: 'git-sa', url: params.gitUrl]]])
					}
				}

      }

			// TODO clean this up
			def ocpConfig = new FileLoader().readConfig("$WORKSPACE/ocp/config.yml")
			ocpConfig << params


			stage("Process Templates") {

				def processedTemplate
				openshift.withProject("project-steve-dev") {
					 def templateSelector = openshift.selector( "template", "releasetemplate")
					 processedTemplate = templateSelector.process("releasetemplate", "-p", "APP_NAME=${ocpConfig.projectName}", "-p", "APP_NAMESPACE=${ocpConfig.ocpnamespace}", "-p", "CONFIG_MAP_REF=${ocpConfig.configMapRef}", "-p", "SECRET_KEY_REF=${ocpConfig.secretKeyRef}", "-p", "READINESS_PROBE=${ocpConfig.readinessProbe}", "-p", "LIVELINESS_PROBE=${ocpConfig.livelinessProbe}")
				}

				openshift.withCluster("ocp-qa") {
					openshift.withProject(params.ocpnamespace) {
						def objectsExist = openshift.selector("all", [ "app" : "${ocpConfig.projectName}" ]).exists()
						if(!objectsExist) {
							openshift.create(processedTemplate)
						}
					}
				}

			}

			stage("Process CM/SK") {

				openshift.withCluster("ocp-qa") {
					openshift.withProject(params.ocpnamespace) {
						// Process Config Map
						Object data = fileLoader.readConfigMap("$WORKSPACE/ocp/dev/${ocpConfig.configMapRef}.yml")
						data.metadata.labels['app'] = "${ocpConfig.projectName}"
						data.metadata.name = "${ocpConfig.configMapRef}"

						def prereqs = openshift.selector( "configmap", "${ocpConfig.configMapRef}" )
						if(!prereqs.exists()) {
							println "ConfigMap ${ocpConfig.configMapRef} doesn't exist, creating now"
							openshift.create(data)
						}
						else {
							println "ConfigMap ${ocpConfig.configMapRef} exists, updating now"
							openshift.apply(data)
						}

						// Process Secret
						data = fileLoader.readSecret("$WORKSPACE/ocp/dev/${ocpConfig.secretKeyRef}.yml")
						data.metadata.labels['app'] = "${ocpConfig.projectName}"
						data.metadata.name = "${ocpConfig.secretKeyRef}"

						prereqs = openshift.selector( "secret", "${ocpConfig.secretKeyRef}" )
						if(!prereqs.exists()) {
							println "Secret ${ocpConfig.secretKeyRef} doesn't exist, creating now"
							openshift.create(data)
						}
						else {
							println "Secret ${ocpConfig.secretKeyRef} exists, updating now"
							openshift.apply(data)
						}

					}
				}
			}

			stage("Verify Rollout") {

			}

	} // end withCluster
} //end runPipeline

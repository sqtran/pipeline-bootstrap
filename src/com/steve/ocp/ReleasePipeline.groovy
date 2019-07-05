package com.steve.ocp

import com.steve.ocp.util.FileLoader

def getLastSuccess() {
	def lastSuccessfulBuildID = 0
	def build = currentBuild.previousBuild
	while (build != null) {
			if (build.result == "SUCCESS")
			{
					lastSuccessfulBuildID = build.id as Integer
					break
			}
			build = build.previousBuild
	}
	return lastSuccessfulBuildID
}

def getEnvPath(def arg) {
	switch(arg?.trim()?.toLowerCase()) {
		case "dev" return "dev"
		case "qa" return "qa"
		case "prod" return "prod"
		default: error("Failing build because deployment environment was not found")
	}
}

def process(def params) {

	def dev_image_tag = "${params.projectName}:${params.selectedImageTag}"
	def qa_image_tag = "${dev_image_tag}-rc-b${getLastSuccess()}"
	def prod_image_tag = "${dev_image_tag}:pom_version"			//TODO

	def env = getEnvPath(ocpConfig.selectedDeploymentEnv)

	openshift.withCluster("ocp-$env") {

    stage('Tag Image in Artifactory') {
      configFileProvider([configFile(fileId: 'docker_config.json', targetLocation: "/home/jenkins/.docker/config.json")]) {

				if(env == "qa") {
					// the ARTIFACTORY_URL also includes the path to store the image in Artifactory
					def results = openshift.raw("image mirror $OCPDEV_REGISTRY_URL/${params.ocpnamespace}/$dev_image_tag $ARTIFACTORY_URL/$qa_image_tag --insecure ")
	        echo "image mirror results = $results"
				}
				else if (env == "prod") {
					// TODO
					echo "tag prod image in Artifactory"
				}

      }
    }

		def img_tag = env == "qa" ? qa_image_tag : prod_image_tag
		echo "Image Tag is $img_tag for $env"


    stage('Get Configs from SCM') {
			// the image has a reference to the git commit's SHA
      def image_info = openshift.raw("image info $ARTIFACTORY_URL/$img_tag --insecure")
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
		def fileLoader = new FileLoader()
		def ocpConfig = fileLoader.readConfig("$WORKSPACE/ocp/config.yml")
		ocpConfig << params

		stage("Process Templates") {
			def template
			openshift.withProject(params.ocpnamespace) {
				if(!openshift.selector("route", [ "app" : "${ocpConfig.projectName}" ]).exists()) {
					openshift.withCluster("ocp-dev") {
						template = openshift.withProject("project-steve-dev") {
							 openshift.selector( "template", "routetemplate").object()
						}
					}
					openshift.create(openshift.process(template, "-p", "APP_NAME=${ocpConfig.projectName}", "-p", "APP_NAMESPACE=${ocpConfig.ocpnamespace}"))
				}

				if(!openshift.selector("service", [ "app" : "${ocpConfig.projectName}" ]).exists()) {
					openshift.withCluster("ocp-dev") {
						template = openshift.withProject("project-steve-dev") {
							 openshift.selector( "template", "servicetemplate").object()
						}
					}
					openshift.create(openshift.process(template, "-p", "APP_NAME=${ocpConfig.projectName}", "-p", "APP_NAMESPACE=${ocpConfig.ocpnamespace}"))
				}

				openshift.withCluster("ocp-dev") {
					template = openshift.withProject("project-steve-dev") {
						 openshift.selector( "template", "deploymentreleasetemplate").object()
					}
				}
				def dc = openshift.process(template, "-p", "DEPLOYMENT_IMAGE=$ARTIFACTORY_URL/$img_tag", "-p", "APP_NAME=${ocpConfig.projectName}", "-p", "APP_NAMESPACE=${ocpConfig.ocpnamespace}", "-p", "CONFIG_MAP_REF=${ocpConfig.configMapRef}", "-p", "SECRET_KEY_REF=${ocpConfig.secretKeyRef}", "-p", "READINESS_PROBE=${ocpConfig.readinessProbe}", "-p", "LIVELINESS_PROBE=${ocpConfig.livelinessProbe}")

				if(!openshift.selector("deploymentconfig", [ "app" : "${ocpConfig.projectName}" ]).exists()) {
					openshift.create(dc)
				}
				else {
					openshift.apply(dc)
				}
			}
		}

		stage("Process CM/SK") {
			openshift.withProject(params.ocpnamespace) {
				// Process Config Map
				Object data = fileLoader.readConfigMap("$WORKSPACE/ocp/$env/${ocpConfig.configMapRef}.yml")
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
				data = fileLoader.readSecret("$WORKSPACE/ocp/$env/${ocpConfig.secretKeyRef}.yml")
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

		stage("Verify Rollout") {
			openshift.withProject(params.ocpnamespace) {

				// Scale if user had specified a specific number of replicas, otherwise just do whatever is already configured in OCP
				def desiredReplicas = ocpConfig.replicas
				if(desiredReplicas != null) {
					openshift.raw("scale deploymentconfig ${ocpConfig.projectName} --replicas=$desiredReplicas")
				}

				timeout(time: 2, unit: 'MINUTES') {
					openshift.selector('dc', ocpConfig.projectName).rollout().status()
				}
			}
		}

	} // end withCluster
} //end process

package com.steve.ocp

@Grab('org.yaml:snakeyaml:1.17')
import org.yaml.snakeyaml.Yaml


def runPipeline(def params) {

	mvnHome = tool 'M3'
	ocHome  = tool 'oc311'
	ocHome  = "$ocHome/openshift-origin-client-tools-v3.11.0-0cbc58b-linux-64bit"
	oc      = "$ocHome/oc"
	def fileLoader = new com.steve.ocp.util.FileLoader()

	stage('Checkout') {
    // not good, but necessary until we fix our self-signed certificate issue
    //sh "git config http.sslVerify false"
    try {
      git branch: "${params.gitBranch}", credentialsId: '6d8ed739-d67d-47f3-8194-c5f3f665da7d', url: "${params.gitUrl}"
    } catch (Exception e) {
      sh "git config http.sslVerify false"
      git branch: "${params.gitBranch}", credentialsId: '6d8ed739-d67d-47f3-8194-c5f3f665da7d', url: "${params.gitUrl}"
    }

    params['gitDigest'] = sh(script: "git rev-parse HEAD", returnStdout: true).trim()
  }

	pom = readMavenPom file: 'pom.xml'
	artifactName = "${pom.name}"
	artifactVersion = "${pom.version}"

	ocpConfig = fileLoader.readConfig("$WORKSPACE/ocp/config.yml")

	// load in the Jenkins parameters into the configuration object so we have everything in one place
	ocpConfig << params

	stage('Build') {
		// Run the maven test
		if (isUnix()) {
			configFileProvider([configFile(fileId: 'custom-settings.xml', variable: 'MAVEN_SETTINGS')]) {
				sh "'${mvnHome}/bin/mvn' -s $MAVEN_SETTINGS clean compile -DskipTests"
			}
		} else {
			error('Failing build because this is not a slave linux container... who knows what will happen')
		}
	}

	stage('Test') {
		// Run the maven test
		if (isUnix()) {
			configFileProvider([configFile(fileId: 'custom-settings.xml', variable: 'MAVEN_SETTINGS')]) {
				sh "'${mvnHome}/bin/mvn' -s $MAVEN_SETTINGS test"
			}
		} else {
			error('Failing build because this is not a slave linux container... who knows what will happen')
		}
	}

	stage('Package') {
		// Run the maven build
		if (isUnix()) {
			configFileProvider([configFile(fileId: 'custom-settings.xml', variable: 'MAVEN_SETTINGS')]) {
				sh "'${mvnHome}/bin/mvn' -s $MAVEN_SETTINGS -Dmaven.test.failure.ignore package -DskipTests"
			}
		} else {
			error('Failing build because this is not a slave linux container... who knows what will happen')
		}
	}

	openshift.withCluster() {
		withEnv(["PATH+OC=$ocHome"]) {
			def objectsExist
			openshift.withProject("${ocpConfig.ocpnamespace}") {
				objectsExist = openshift.selector("all", [ application : "${ocpConfig.projectName}" ]).exists()

				stage("Process CM/SK") {

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

				} // end stage
			} // end withProject

			openshift.withProject("project-steve-dev") {
				def templateSelector = openshift.selector( "template", "stevetemplate")
				stage('Process Template') {
					if(!objectsExist) {
						// TODO loop through this to process each parameter
						openshift.create(templateSelector.process("stevetemplate", "-p", "APP_NAME=${ocpConfig.projectName}", "-p", "APP_NAMESPACE=${ocpConfig.ocpnamespace}", "-p", "CONFIG_MAP_REF=${ocpConfig.configMapRef}", "-p", "SECRET_KEY_REF=${ocpConfig.secretKeyRef}", "-p", "READINESS_PROBE=${ocpConfig.readinessProbe}", "-p", "LIVELINESS_PROBE=${ocpConfig.livelinessProbe}"))
					}
				}
			}

			openshift.withProject("${ocpConfig.ocpnamespace}") {
				def bc = openshift.selector("buildconfig", "${ocpConfig.projectName}")

				stage('OCP Upload Binary') {
					sh "mkdir -p target/ocptarget/.s2i && mv target/${artifactName}.jar target/ocptarget && echo \"GIT_REF=${ocpConfig.gitDigest}\" > target/ocptarget/.s2i/environment"
					bc.startBuild("--from-dir=target/ocptarget")
					bc.logs("-f")
				}

				stage ('Verify Build') {
					def builds = bc.related('builds')
					builds.watch {
						if ( it.count() == 0 ) return false
						// A robust script should not assume that only one build has been created, so we will need to iterate through all builds.
						def allDone = true
						it.withEach {
							// 'it' is now bound to a Selector selecting a single object for this iteration.  Let's model it in Groovy to check its status.
							def buildModel = it.object()
							if ( it.object().status.phase != "Complete" ) {
								allDone = false
							}
						}
						return allDone;
					}
					openshift.tag("${ocpConfig.projectName}:latest", "${ocpConfig.projectName}:${artifactVersion}-b${currentBuild.number}")
				}

				stage ('Verify Deploy') {
					def latestDeploymentVersion = openshift.selector('dc',"${ocpConfig.projectName}").object().status.latestVersion
					def rc = openshift.selector('rc', "${ocpConfig.projectName}-${latestDeploymentVersion}")
					timeout(time: 2, unit: 'MINUTES') {
						rc.untilEach(1) {
							def rcMap = it.object()
							return (rcMap.status.replicas.equals(rcMap.status.readyReplicas))
						}
					}
				} // end stage
			} // end withProject
		} // end withEnv
	} // end withCluster
} //end runPipeline

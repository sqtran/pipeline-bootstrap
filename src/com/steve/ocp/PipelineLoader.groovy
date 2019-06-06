package com.steve.ocp

@Grab('org.yaml:snakeyaml:1.17')
import org.yaml.snakeyaml.Yaml


def runPipeline(def params) {

	mvnHome = tool 'M3'
	ocHome  = tool 'oc311'
	ocHome  = "$ocHome/openshift-origin-client-tools-v3.11.0-0cbc58b-linux-64bit"
	oc      = "$ocHome/oc"

	pom = readMavenPom file: 'pom.xml'
	artifactName = "${pom.name}"
	artifactVersion = "${pom.version}"

  def fileLoader = new com.steve.ocp.util.FileLoader()


	ocpConfig = fileLoader.readConfig("$WORKSPACE/ocp/config.yml")

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
			openshift.withProject("${params.ocpnamespace}") {
				objectsExist = openshift.selector("all", [ application : "${params.projectName}" ]).exists()

				stage("Process CM/SK") {

					// Process Config Map
					Object data = fileLoader.readConfigMap("$WORKSPACE/ocp/dev/${ocpConfig.configMapRef}.yml")
					data.metadata.labels['app'] = "${params.projectName}"
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
					data.metadata.labels['app'] = "${params.projectName}"
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
						openshift.create(templateSelector.process("stevetemplate", "-p", "APP_NAME=${params.projectName}", "-p", "APP_NAMESPACE=${params.ocpnamespace}", "-p", "CONFIG_MAP_REF=${params.configMapRef}", "-p", "SECRET_KEY_REF=${params.secretKeyRef}", "-p", "READINESS_PROBE=${ocpConfig.readinessProbe}", "-p", "LIVELINESS_PROBE=${ocpConfig.livelinessProbe}"))
					}
				}
			}

			openshift.withProject("${params.ocpnamespace}") {
				def bc = openshift.selector("buildconfig", "${params.projectName}")

				stage('OCP Upload Binary') {
					sh "mkdir -p target/ocptarget/.s2i && mv target/${artifactName}.jar target/ocptarget && echo \"GIT_REF=${params.gitDigest}\" > target/ocptarget/.s2i/environment"
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
					openshift.tag("${params.projectName}:latest", "${params.projectName}:${params.artifactVersion}-b${currentBuild.number}")
				}

				stage ('Verify Deploy') {
					def latestDeploymentVersion = openshift.selector('dc',"${params.projectName}").object().status.latestVersion
					def rc = openshift.selector('rc', "${params.projectName}-${latestDeploymentVersion}")
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

package com.steve.ocp

import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

def stage(name, execute, block) {
    return stage(name, execute ? block : {
        echo "skipped stage $name"
        Utils.markStageSkippedForConditional(STAGE_NAME)
    })
}

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
      git branch: "${params.gitBranch}", credentialsId: 'git-sa', url: "${params.gitUrl}"
    } catch (Exception e) {
      sh "git config http.sslVerify false"
      git branch: "${params.gitBranch}", credentialsId: 'git-sa', url: "${params.gitUrl}"
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
				objectsExist = openshift.selector("all", [ "app" : "${ocpConfig.projectName}" ]).exists()

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
				stage('Process Template', !objectsExist) {
					// TODO loop through this to process each parameter
					openshift.create(templateSelector.process("stevetemplate", "-p", "APP_NAME=${ocpConfig.projectName}", "-p", "APP_NAMESPACE=${ocpConfig.ocpnamespace}", "-p", "CONFIG_MAP_REF=${ocpConfig.configMapRef}", "-p", "SECRET_KEY_REF=${ocpConfig.secretKeyRef}", "-p", "READINESS_PROBE=${ocpConfig.readinessProbe}", "-p", "LIVELINESS_PROBE=${ocpConfig.livelinessProbe}"))
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

				stage ('Verify DEV Deploy') {
					def latestDeploymentVersion = openshift.selector('dc',"${ocpConfig.projectName}").object().status.latestVersion
					def rc = openshift.selector('rc', "${ocpConfig.projectName}-${latestDeploymentVersion}")
					timeout(time: 2, unit: 'MINUTES') {
						rc.untilEach(1) {
							def rcMap = it.object()
							return (rcMap.status.replicas.equals(rcMap.status.readyReplicas))
						}
					}
				} // end stage

				def userInput = false
				def didTimeout = false

				stage ('Promote to QA?') {
					try {
					    timeout(time: 120, unit: 'SECONDS') {
					        userInput = input(
					        	id: 'PromoteToQA', message: 'Promote to QA?', parameters: [
					        		[$class: 'BooleanParameterDefinition', defaultValue: false, description: 'Check box to promote this to QA', name: 'Please confirm you agree with this']
					        	]
									)
					    }
					} catch(err) { // timeout reached or input false
					    def user = err.getCauses()[0].getUser()
					    if('SYSTEM' == user.toString()) { // SYSTEM means timeout.
					        didTimeout = true
					    } else {
					        userInput = false
					        echo "Aborted by: [${user}]"
					    }
					}

			    if (didTimeout) {
			        // do something on timeout
			        echo "no input was received before timeout"
			    } else if (userInput) {
			        // do something
			        echo "this was successful"
			    } else {
			        // do something else
			        echo "this was not successful"
			        currentBuild.result = 'FAILURE'
			    }
				}

				stage ('Promote to PROD?', userInput) {
					echo "Promote to PROD? stage"
				}
				stage ('Tag', userInput) {
					echo "Tag stage"
				}
				stage ('Push to Artifactory', userInput) {
					echo "Push to Artifactory stage"
				}
				stage ('Verify PROD Deploy', userInput) {
					echo "Verify PROD Deploy stage"
				}

			} // end withProject
		} // end withEnv
	} // end withCluster
} //end runPipeline

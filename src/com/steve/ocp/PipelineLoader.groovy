package com.steve.ocp

import org.jenkinsci.plugins.pipeline.modeldefinition.Utils
import com.steve.ocp.util.FileLoader
import com.steve.ocp.util.Sanitizer

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
	def fileLoader = new FileLoader()

  // we sanitize because we assume all input is valid from here on out
  params = new Sanitizer().sanitizePipelineInput(params)

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

        // Job ID is the first 8 characters of the UUID based on the JOB_NAME
        def jid = UUID.nameUUIDFromBytes("$JOB_NAME".getBytes()).toString().substring(0,8);
        def current_image_tag = "${ocpConfig.projectName}:${artifactVersion}-${jid}-b${currentBuild.number}"
        println "This Pipeline has Job ID $jid and will tag the newly created image as $current_image_tag"

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
					openshift.tag("${ocpConfig.projectName}:latest", current_image_tag)
				}

				stage ('Verify DEV Deploy') {

          // Scale if user had specified a specific number of replicas, otherwise just do whatever is already configured in OCP
          def desiredReplicas = ocpConfig.replicas
          if(desiredReplicas != null) {
            openshift.raw("scale deploymentconfig ${ocpConfig.projectName} --replicas=$desiredReplicas")
          }

					timeout(time: 2, unit: 'MINUTES') {
            openshift.selector('dc', ocpConfig.projectName).rollout().status()
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
			        echo "User selected to promote"
			    } else {
			        // do something else
			        echo "User selected not to promote"
			        //currentBuild.result = 'FAILURE'
			    }
				}

				stage('Push into Artifactory', userInput) {
				  configFileProvider([configFile(fileId: 'docker_config.json', targetLocation: "/home/jenkins/.docker/config.json")]) {
							// the ARTIFACTORY_URL also includes the path to store the image in Artifactory
							def results = openshift.raw("image mirror $OCPDEV_REGISTRY_URL/${ocpConfig.ocpnamespace}/$current_image_tag $ARTIFACTORY_URL/$current_image_tag --insecure ")
							echo "image mirror results = $results"
				  }
				}

				stage ('Verify QA Deploy', userInput) {
          echo "Verify QA Deploy stage"
				}
				stage ('Promote to PROD?', userInput) {
					echo "Promote to PROD? stage"
				}
				stage ('Verify PROD Deploy', userInput) {
					echo "Verify PROD Deploy stage"
				}

			} // end withProject
		} // end withEnv
	} // end withCluster
} //end runPipeline

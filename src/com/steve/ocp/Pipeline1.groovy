@Grab('org.yaml:snakeyaml:1.17')
import org.yaml.snakeyaml.Yaml

def runPipeline() {

/*
    def mvnHome
    def artifactName
    def artifactVersion
    def projectName = "$PROJECT_NAME"
    def gitProject = "$GIT_URL"
    def gitBranch = "$GIT_BRANCH"
    def gitDigest
    def ocpnamespace = "$OCP_NAMESPACE"
    def configMapRef = "${ CONFIG_MAP_REF ?: "$PROJECT_NAME-cm" }"
    def secretKeyRef = "${ SECRET_KEY_REF ?: "$PROJECT_NAME-sk" }"
    def readinessProbe = "$READINESS_PROBE"
    def livelinessProbe = "$LIVELINESS_PROBE"
    def currentBuild = "\${currentBuild.number}"
*/

    stage('Build') {
      // Run the maven test
      if (isUnix()) {
        configFileProvider([configFile(fileId: 'odlf-settings.xml', variable: 'MAVEN_SETTINGS')]) {
          sh "'${mvnHome}/bin/mvn' -s $MAVEN_SETTINGS clean compile -DskipTests"
        }
      } else {
        error('Failing build because this is not a slave linux container... who knows what will happen')
      }
    }

    stage('Test') {
      // Run the maven test
      if (isUnix()) {
        configFileProvider([configFile(fileId: 'odlf-settings.xml', variable: 'MAVEN_SETTINGS')]) {
          sh "'${mvnHome}/bin/mvn' -s $MAVEN_SETTINGS test"
        }
      } else {
        error('Failing build because this is not a slave linux container... who knows what will happen')
      }
  	}

    stage('Package') {
      // Run the maven build
      if (isUnix()) {
        configFileProvider([configFile(fileId: 'odlf-settings.xml', variable: 'MAVEN_SETTINGS')]) {
          sh "'${mvnHome}/bin/mvn' -s $MAVEN_SETTINGS -Dmaven.test.failure.ignore package -DskipTests"
        }
      } else {
        error('Failing build because this is not a slave linux container... who knows what will happen')
      }
    }

    openshift.withCluster() {
      withEnv(["PATH+OC=\$ocHome"]) {
  		  def objectsExist
  			openshift.withProject(ocpnamespace) {
          objectsExist = openshift.selector("all", [ application : projectName ]).exists()

  				//stage("Check SA Permissions") {
          //    openshift.raw('adm', "policy", "add-role-to-user", "edit", "system:serviceaccount:project-steve-dev:jenkins")
          //    openshift.raw('adm', "policy", "add-role-to-user", "system:image-builder", "system:serviceaccount:project-steve-dev:jenkins")
          //}

          stage("Process CM/SK") {
            def prereqs
            def fileName
            Object data

  					fileName = "$WORKSPACE/ocp/dev/${configMapRef}.yml"
            data = new Yaml().load(new FileInputStream(new File(fileName)))

  				  if(new File(fileName).exists()) {
              // Sanitize config map by removing the name and namespace
              data.metadata.remove('namespace')
  						if(data.metadata.labels == null) {
                data.metadata.labels = [:]
              }
  						data.metadata.labels['application'] = projectName
  	    			data.metadata.name = configMapRef
              prereqs = openshift.selector( "configmap", configMapRef )
              if(!prereqs.exists()) {
                println "ConfigMap \$configMapRef doesn't exist, creating now"
                openshift.create(data)
              }
              else {
                println "ConfigMap $configMapRef exists, updating now"
                openshift.apply(data)
              }
  					}

            fileName = "$WORKSPACE/ocp/dev/${secretKeyRef}.yml"
  					if(new File(fileName).exists()) {
              data = new Yaml().load(new FileInputStream(new File(fileName)))
              // Sanitize config map by removing namespace
              data.metadata.remove('namespace')
  						if(data.metadata.labels == null) {
                data.metadata.labels = [:]
              }
      				data.metadata.labels['application'] = projectName
  						data.metadata.name = secretKeyRef
              prereqs = openshift.selector( "secret", secretKeyRef )
              if(!prereqs.exists()) {
                println "Secret $secretKeyRef doesn't exist, creating now"
                openshift.create(data)
              }
              else {
                println "Secret $secretKeyRef exists, updating now"
                openshift.apply(data)
              }
  					}
          }
  			}

  			openshift.withProject("project-steve-dev") {
          def templateSelector = openshift.selector( "template", "stevetemplate")
          stage('Process Template') {
  				    if(!objectsExist) {
  						      // TODO loop through this to process each parameter
  						      openshift.create(templateSelector.process("stevetemplate", "-p", "APP_NAME=$projectName", "-p", "APP_NAMESPACE=$ocpnamespace", "-p", "CONFIG_MAP_REF=$configMapRef", "-p", "SECRET_KEY_REF=$secretKeyRef", "-p", "READINESS_PROBE=$readinessProbe", "-p", "LIVELINESS_PROBE=$livelinessProbe"))
  	          }
          }
  			}

  			openshift.withProject(ocpnamespace) {
          def bc = openshift.selector("buildconfig", projectName)
          stage('OCP Upload Binary') {
  				    sh "mkdir -p target/ocptarget/.s2i && mv target/\${artifactName}.jar target/ocptarget && echo \"GIT_REF=$gitDigest\" > target/ocptarget/.s2i/environment"
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

              openshift.tag("\${projectName}:latest", "\${projectName}:\${artifactVersion}-b\${currentBuild}")

          }

          stage ('Verify Deploy') {
            def latestDeploymentVersion = openshift.selector('dc',"\${projectName}").object().status.latestVersion
            def rc = openshift.selector('rc', "\${projectName}-\${latestDeploymentVersion}")
     				timeout(time: 2, unit: 'MINUTES') {
              rc.untilEach(1){
                def rcMap = it.object()
                return (rcMap.status.replicas.equals(rcMap.status.readyReplicas))
              }
            }
          }
        }
   		}
    }
} //end Pipeline1

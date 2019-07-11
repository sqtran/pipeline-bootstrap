
package com.steve.ocp


def process(def params) {



  stage('Checkout') {
    // not good, but necessary until we fix our self-signed certificate issue
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

  evaluate(new File("./src/com/steve/ocp/util/FileLoader.groovy"))

  def fileLoader = new FileLoader()

  ocpConfig = fileLoader.readConfig("$WORKSPACE/ocp/config.yml")


  openshift.withCluster() {
    openshift.withProject("steve-test1") {
      echo "Hello from project ${openshift.project()} in cluster ${openshift.cluster()} with params $params"
    }
  }
}

return this

package com.steve.ocp.util

import com.steve.ocp.util.FileLoader

def processCMSK(def ocpConfig, def env) {
  openshift.withCluster("ocp-$env") {
    openshift.withProject(ocpConfig.ocpnamespace) {
      def fileLoader = new FileLoader()

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

    } // end withProject
  } //end withCluster
} //end processCMSK

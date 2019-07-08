package com.steve.ocp.util

import groovy.transform.Field

@Field def jenkinsnamespace = "project-steve-dev"
@Field def jenkinscluster   = "ocp-dev"
@Field def qaCluster        = "ocp-qa"
@Field def prodCluster      = "ocp-prod"

def processBuildTemplates(def params) {
  openshift.withCluster(jenkinscluster) {
    openshift.withProject(params.ocpnamespace) {
      processObject(params.projectName, params.ocpnamespace, "buildconfig")
      processObject(params.projectName, params.ocpnamespace, "imagestream")
      processObject(params.projectName, params.ocpnamespace, "route")
      processObject(params.projectName, params.ocpnamespace, "service")
      processDevelopmentDC(params)
    }
  }
}

def processReleaseTemplates(def params, def deploymentImageName) {
  openshift.withCluster(qaCluster) {
    openshift.withProject(params.ocpnamespace) {
      processObject(params.projectName, params.ocpnamespace, "route")
      processObject(params.projectName, params.ocpnamespace, "service")
      processReleaseDC(params, deploymentImageName)
    }
  }
}

def getTemplateName(def objectName) {
	switch(objectName?.trim()?.toLowerCase()) {
		case "buildconfig": return "buildtemplate"
		case "imagestream": return "imagestreamtemplate"
		case "route": return "routetemplate"
    case "service": return "servicetemplate"
		default: error("Failing build because invalid OCP object passed in")
	}
}

def processObject(def appName, def appNamespace, def ocpObject) {
  def template
  if(!openshift.selector(ocpObject, [ "app" : appName ]).exists()) {
    openshift.withCluster(jenkinscluster) {
      template = openshift.withProject(jenkinsnamespace) {
         openshift.selector( "template", getTemplateName(ocpObject)).object()
      }
    }
    println "Creating $ocpObject now"
    openshift.create(openshift.process(template, "-p", "APP_NAME=$appName", "-p", "APP_NAMESPACE=$appNamespace"))
  }
}

def processDevelopmentDC(def params) {
  def template
  if(!openshift.selector("deploymentconfig", [ "app" : params.projectName ]).exists()) {
    openshift.withCluster(jenkinscluster) {
      template = openshift.withProject(jenkinsnamespace) {
         openshift.selector( "template", "deploymentconfigtemplate-build").object()
      }
    }
    println "Creating deploymentconfig now"
    def dc = openshift.process(template, "-p", "APP_NAME=${params.projectName}", "-p", "APP_NAMESPACE=${params.ocpnamespace}", "-p", "CONFIG_MAP_REF=${params.configMapRef}", "-p", "SECRET_KEY_REF=${params.secretKeyRef}", "-p", "READINESS_PROBE=${params.readinessProbe}", "-p", "LIVELINESS_PROBE=${params.livelinessProbe}")
    openshift.create(dc)
  }
}


def processReleaseDC(def params, def deploymentImageName) {
  def template
  openshift.withCluster(jenkinscluster) {
    template = openshift.withProject(jenkinsnamespace) {
       openshift.selector( "template", "deploymentconfigtemplate-release").object()
    }
  }
  def dc = openshift.process(template, "-p", "DEPLOYMENT_IMAGE=$deploymentImageName", "-p", "APP_NAME=${params.projectName}", "-p", "APP_NAMESPACE=${params.ocpnamespace}", "-p", "CONFIG_MAP_REF=${params.configMapRef}", "-p", "SECRET_KEY_REF=${params.secretKeyRef}", "-p", "READINESS_PROBE=${params.readinessProbe}", "-p", "LIVELINESS_PROBE=${params.livelinessProbe}")

  if(!openshift.selector("deploymentconfig", [ "app" : "${params.projectName}" ]).exists()) {
    println "Creating deploymentconfig now"
    openshift.create(dc)
  }
  else {
    println "Updating deploymentconfig now"
    openshift.apply(dc)
  }
}

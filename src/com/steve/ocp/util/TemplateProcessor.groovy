package com.steve.ocp.util

import groovy.transform.Field

@Field def jenkinsnamespace = "project-steve-dev"
@Field def jenkinscluster   = "ocp-dev"
@Field def qaCluster        = "ocp-qa"
@Field def prodCluster      = "ocp-prod"


def processReleaseTemplates(def params, def deploymentImageName) {
  openshift.withCluster("ocp-qa") {
    openshift.withProject(params.ocpnamespace) {
      processRT(params)
      processSVC(params)
      processReleaseDC(params, deploymentImageName)
    }
  }
}

def processRT(def params) {
  def template
  if(!openshift.selector("route", [ "app" : "${params.projectName}" ]).exists()) {
    openshift.withCluster(jenkinscluster) {
      template = openshift.withProject(jenkinsnamespace) {
         openshift.selector( "template", "routetemplate").object()
      }
    }
    println "Creating Route now"
    openshift.create(openshift.process(template, "-p", "APP_NAME=${params.projectName}", "-p", "APP_NAMESPACE=${params.ocpnamespace}"))
  }
}

def processSVC(def params) {
  def template
  if(!openshift.selector("service", [ "app" : "${params.projectName}" ]).exists()) {
    openshift.withCluster(jenkinscluster) {
      template = openshift.withProject(jenkinsnamespace) {
         openshift.selector( "template", "servicetemplate").object()
      }
    }
    println "Creating Service now"
    openshift.create(openshift.process(template, "-p", "APP_NAME=${params.projectName}", "-p", "APP_NAMESPACE=${params.ocpnamespace}"))
  }
}

def processBuildDC(def params) {
  /*
  openshift.withProject(params.ocpnamespace) {
    if(!openshift.selector("service", [ "app" : "${params.projectName}" ]).exists()) {
      openshift.withCluster(jenkinscluster) {
        template = openshift.withProject(jenkinsnamespace) {
           openshift.selector( "template", "servicetemplate").object()
        }
      }
      openshift.create(openshift.process(template, "-p", "APP_NAME=${params.projectName}", "-p", "APP_NAMESPACE=${params.ocpnamespace}"))
    }
  }
  */
}


def processReleaseDC(def params, def deploymentImageName) {
  def template
  openshift.withCluster(jenkinscluster) {
    template = openshift.withProject(jenkinsnamespace) {
       openshift.selector( "template", "deploymentreleasetemplate").object()
    }
  }
  def dc = openshift.process(template, "-p", "DEPLOYMENT_IMAGE=$deploymentImageName", "-p", "APP_NAME=${params.projectName}", "-p", "APP_NAMESPACE=${params.ocpnamespace}", "-p", "CONFIG_MAP_REF=${params.configMapRef}", "-p", "SECRET_KEY_REF=${params.secretKeyRef}", "-p", "READINESS_PROBE=${params.readinessProbe}", "-p", "LIVELINESS_PROBE=${params.livelinessProbe}")

  if(!openshift.selector("deploymentconfig", [ "app" : "${params.projectName}" ]).exists()) {
    println "Creating DeploymentConfig now"
    openshift.create(dc)
  }
  else {
    println "Updating DeploymentConfig now"
    openshift.apply(dc)
  }
}

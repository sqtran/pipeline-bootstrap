package com.steve.ocp.util

def rollout(def projectName, def desiredReplicas) {

  // Scale if user had specified a specific number of replicas, otherwise just do whatever is already configured in OCP
  if(desiredReplicas != null && desiredReplicas != "") {
    openshift.raw("scale deploymentconfig $projectName --replicas=$desiredReplicas")
  }

  timeout(time: 2, unit: 'MINUTES') {
    openshift.selector('deploymentconfig', projectName).rollout().latest()
    openshift.selector('deploymentconfig', projectName).rollout().status()
  }

}

return this

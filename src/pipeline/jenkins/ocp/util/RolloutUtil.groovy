package pipeline.jenkins.ocp.util

def rollout(def configs) {

  // Scale if user had specified a specific number of replicas, otherwise just do whatever is already configured in OCP
  if(configs?.replicas != null) {
    openshift.raw("scale deploymentconfig ${configs.projectName} --replicas=${configs.replicas}")
  }

  if(configs?.readinessProbe?.trim() != "") {
    openshift.raw("""set probe deploymentconfig/${configs.projectName} --readiness --get-url="//:8080${getProbe(configs.readinessProbe)}" """)
  }
  // the correct term is liveness but some people have been passing in liveliness
  if(configs?.livelinessProbe?.trim() != "" || configs?.livenessProbe?.trim() != "") {
    openshift.raw("""set probe deploymentconfig/${configs.projectName} --liveness --get-url="//:8080${getProbe(configs.livelinessProbe ?: configs.livenessProbe)}" """)
  }

  timeout(time: 2, unit: 'MINUTES') {
    openshift.selector('deploymentconfig', "${configs.projectName}").rollout().latest()
    openshift.selector('deploymentconfig', "${configs.projectName}").rollout().status()
  }
}

def getProbe(def url) {
 return url?.startsWith("/") ? url : "/$url"
}

return this

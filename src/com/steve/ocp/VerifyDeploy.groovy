def verifyDeployment(String projectName) {
  stage ('Verify Deploy') {
    def latestDeploymentVersion = openshift.selector('dc',"${projectName}").object().status.latestVersion
    def rc = openshift.selector('rc', "${projectName}-${latestDeploymentVersion}")
    timeout(time: 2, unit: 'MINUTES') {
      rc.untilEach(1){
        def rcMap = it.object()
        return (rcMap.status.replicas.equals(rcMap.status.readyReplicas))
      }
    }
  }
}

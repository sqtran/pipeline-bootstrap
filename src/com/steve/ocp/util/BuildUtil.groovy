package com.steve.ocp.util

def start(def buildConfig, def artifactName, def envs) {
  def envstring = envs.inject([]) { result, entry ->
      result << "${entry.key}=${entry.value}"
  }.join("\n")

  def bc = openshift.selector("buildconfig", buildConfig)
  sh """mkdir -p target/ocptarget/.s2i && find -type f \\( -iname '*.jar' -not -iname '*-sources.jar' \\) -exec mv {} target/ocptarget/${artifactName}.jar \\; && printf "$envstring" > target/ocptarget/.s2i/environment"""
  bc.startBuild("--from-dir=target/ocptarget")
  bc.logs("-f")
}

def verify(def buildConfig) {
  def bc = openshift.selector("buildconfig", buildConfig)
  def builds = bc.related('builds')

  // loops through and finds the last build number
  def latestBuild = -1
  builds.withEach {
    def buildNum = it.object().metadata.annotations["openshift.io/build.number"] as int
    if(buildNum > latestBuild) {
      latestBuild = buildNum
    }
  }

  // only wait up to 2 minutes
  timeout(2) {
    builds.watch {

      if ( it.count() == 0 ) return false
      // A robust script should not assume that only one build has been created, so we will need to iterate through all builds.
      def allDone = true
      it.withEach {

        // 'it' is now bound to a Selector selecting a single object for this iteration.  Let's model it in Groovy to check its status.
        def buildModel = it.object()
        def buildNum = buildModel.metadata.annotations["openshift.io/build.number"] as int
        if( buildNum != latestBuild) {
          // not the latest build, we don't care about this one
          return
        }

        if ( it.object().status.phase != "Complete" ) {
          allDone = false
        }
      }
      return allDone;
    }
  }
}

return this

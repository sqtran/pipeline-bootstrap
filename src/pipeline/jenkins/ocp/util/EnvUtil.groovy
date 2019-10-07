package pipeline.jenkins.ocp.util

def resetEnvs(def dcName, def containerName = null) {
  if(containerName == null) {
    containerName = dcName
  }
  openshift.raw(""" patch dc/$dcName  -p  '{"spec": {"template": {"spec": {"containers": [{"name": "$containerName", "env": null}] }}}}'  """)
}

def processCM(def cmName, def dcName, def data) {
  process(cmName, dcName, data, "configmap")
}

def processSK(def skName, def dcName, def data) {
  process(skName, dcName, data, "secret")
}

def process(def objName, def dcName, def data, def type) {

  data.metadata.labels['app'] = dcName
  data.metadata.name = objName

  def prereqs = openshift.selector( type, objName )
  if(!prereqs.exists()) {
    println "$type $objName doesn't exist, creating now"
    openshift.create(data)
  }
  else {
    println "$type $objName exists, updating now"
    openshift.apply(data)
  }

  try {
    openshift.raw("set env dc/$dcName --from $type/$objName")
  } catch (Exception e) {

  }
}

return this

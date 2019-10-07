package pipeline.jenkins.ocp.util

def sanitizePipelineInput(def params) {
  def clean = [:]
  if(params instanceof Map) {
    params.each { clean[it.key.trim()] = params[it.key].trim() }
  }
  return clean
}

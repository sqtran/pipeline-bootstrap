package com.steve.ocp.util

@Grab('org.yaml:snakeyaml:1.17')
import org.yaml.snakeyaml.Yaml

def readConfig(def filePath = "./ocp/config.yml") {
	String[] requiredFields = ["readinessProbe", "livelinessProbe", "secretKeyRef", "configMapRef"]
	String[] positiveIntFields = ["replicas"]
	def config = readYamlFile(filePath, "A config.yml file is required")

	StringBuilder err = new StringBuilder("")
	requiredFields.each {
		if(config["${it}"] == null || config["${it}"] == "") {
			err.append("${it} is a required field\n")
		}
	}

	positiveIntFields.each {
		if(config["${it}"] != null && config["${it}"] < 0) {
			err.append("${it} must be a positive integer\n")
		}
	}

	if(err.toString() != "") {
		throw new RuntimeException(err.toString())
	}
	return config
}

def readConfigMap(def filePath) {
	def data
	try {
		def f = readFile filePath
		data = new Yaml().load(f)
	} catch(Exception e) {
		data = [
			"apiVersion": "v1",
			"kind": "ConfigMap",
			"metadata": [
				"creationTimestamp": null,
			]
		]
	}

	return sanitize(data)
}

def readSecret(def filePath) {
	def data
	try {
		def f = readFile filePath
		data = new Yaml().load(f)
	} catch(Exception e) {
		data = [
			"apiVersion": "v1",
			"kind": "Secret",
			"data": [
				"default": ""
			],
			"metadata": [
				"creationTimestamp": null,
			],
			"type": "Opaque"
		]
	}

	return sanitize(data)
}

def sanitize(def data) {
	// Sanitize by removing the name and namespace
	data?.metadata?.remove('namespace')
	if(data?.metadata?.labels == null && data?.metadata != null) {
		// only set metadata on objects that have it
		data?.metadata?.labels = [:]
	}
	return data
}

def readYamlFile(def filePath, String errMessage) {
	try {
		def f = readFile filePath
		def data = new Yaml().load(f)
		return sanitize(data)
	} catch(FileNotFoundException fnfe) {
		throw new RuntimeException(errMessage)
	}
}

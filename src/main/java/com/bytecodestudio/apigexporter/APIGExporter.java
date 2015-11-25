package com.bytecodestudio.apigexporter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.services.apigateway.AmazonApiGateway;
import com.amazonaws.services.apigateway.model.ApiGateway;
import com.amazonaws.services.apigateway.model.Integration;
import com.amazonaws.services.apigateway.model.IntegrationResponse;
import com.amazonaws.services.apigateway.model.Method;
import com.amazonaws.services.apigateway.model.MethodResponse;
import com.amazonaws.services.apigateway.model.Model;
import com.amazonaws.services.apigateway.model.Models;
import com.amazonaws.services.apigateway.model.Resource;
import com.amazonaws.services.apigateway.model.RestApi;
import com.amazonaws.services.cloudfront.model.InvalidArgumentException;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.wordnik.swagger.models.ArrayModel;
import com.wordnik.swagger.models.ComposedModel;
import com.wordnik.swagger.models.Info;
import com.wordnik.swagger.models.ModelImpl;
import com.wordnik.swagger.models.Operation;
import com.wordnik.swagger.models.Path;
import com.wordnik.swagger.models.RefModel;
import com.wordnik.swagger.models.Response;
import com.wordnik.swagger.models.Scheme;
import com.wordnik.swagger.models.Swagger;
import com.wordnik.swagger.models.auth.ApiKeyAuthDefinition;
import com.wordnik.swagger.models.auth.In;
import com.wordnik.swagger.models.parameters.BodyParameter;
import com.wordnik.swagger.models.parameters.HeaderParameter;
import com.wordnik.swagger.models.parameters.Parameter;
import com.wordnik.swagger.models.parameters.PathParameter;
import com.wordnik.swagger.models.parameters.QueryParameter;
import com.wordnik.swagger.models.properties.ArrayProperty;
import com.wordnik.swagger.models.properties.MapProperty;
import com.wordnik.swagger.models.properties.Property;
import com.wordnik.swagger.models.properties.PropertyBuilder;
import com.wordnik.swagger.models.properties.RefProperty;
import com.wordnik.swagger.models.properties.StringProperty;
import com.wordnik.swagger.util.Json;
import com.wordnik.swagger.util.Yaml;

public class APIGExporter {

	private static final String SECURITY_API_KEY = "api_key";
	private static final String METHOD_REQUEST_HEADER = "method.request.header.";
	private static final String METHOD_REQUEST_QUERYSTRING = "method.request.querystring.";
	private static final String METHOD_REQUEST_PATH = "method.request.path.";
    private static final String METHOD_RESPONSE_HEADER = "method.response.header.";
	
    private static final String DEFAULT_CONTENT_TYPE = "application/json";
    private static final String EXTENSION_AUTH = "x-amazon-apigateway-auth";
    private static final String EXTENSION_INTEGRATION = "x-amazon-apigateway-integration";

    private static final String USER_AGENT = "AmazonApiGatewayExporter/1.0";
	
    private static enum ConsumesProducesEnum {
    	CONSUMES, PRODUCES
    }
    
	private AWSCredentialsProvider credsProvider;
	private String region;
    
    public APIGExporter(AWSCredentialsProvider credsProvider, String region) {
		this.credsProvider = credsProvider;
		this.region = region;
    	
    }
    
	public String export(String restApiId, String format) throws IOException {
		boolean inlineBodyParameterSchema = true;
		boolean inlineResponseSchema = true;
		
        ClientConfiguration clientConfig = new ClientConfiguration().withUserAgent(USER_AGENT);
		ApiGateway apiGateway = new AmazonApiGateway(getEndpoint(region)).with(credsProvider).with(clientConfig).getApiGateway();
		RestApi restApi = apiGateway.getRestApiById(restApiId);
		
		String basePath = getBasePath(restApi);
		Swagger swagger = new Swagger()
				.info(new Info().title(restApi.getName()).description(restApi.getDescription()))
				.host(restApiId + ".execute-api." + region + ".amazonaws.com")
				.scheme(Scheme.HTTPS)
				.basePath(basePath);
		
		swagger.setDefinitions(getDefinitions(restApi));
		
		Map<String, Integer> modelRefCount = new HashMap<String, Integer>();
		processModelRefs(swagger.getDefinitions().values(), modelRefCount);
		
		boolean addApiKey = false;
		boolean consumesDefaultContentType = false;
		List<String> consumesContentType = null;
		boolean producesDefaultContentType = false;
		List<String> producesContentType = null;
		Map<String, Path> paths = new HashMap<String, Path>();
		for (Resource resource : restApi.getResources().getItem()) {
			Map<String, Method> resourceMethods = resource.getResourceMethods();
			if (resourceMethods.isEmpty()) {
				continue;
			}
			Path path = new Path();			
			for (Method method : resourceMethods.values()) {
				Operation operation = new Operation();
//				operation.setSummary(summary);
//				operation.setDescription(description);
				
				Map<String, String> requestModels = method.getRequestModels();
				if (requestModels != null && !requestModels.isEmpty()) {
					String requestModelName = requestModels.get(DEFAULT_CONTENT_TYPE);
					if (requestModelName == null) {
						requestModelName = requestModels.values().iterator().next();
					} else {
						consumesDefaultContentType = true;
					}
					operation.addParameter(getBodyParameter(requestModelName, inlineBodyParameterSchema, swagger, modelRefCount));
					updateOperationConsumesProduces(ConsumesProducesEnum.CONSUMES, operation, requestModels.keySet());
				}
				Map<String, Boolean> requestParameters = method.getRequestParameters();
				if (requestParameters != null) {
					for (Map.Entry<String, Boolean> parameterEntry : requestParameters.entrySet()) {
						operation.addParameter(getParameter(parameterEntry));
					}
				}
				Map<String, MethodResponse> methodResponses = method.getMethodResponses();
				if (methodResponses != null) {
					for (Map.Entry<String, MethodResponse> responseEntry : methodResponses.entrySet()) {
						MethodResponse methodResponse = responseEntry.getValue();
						Response response = new Response().headers(getResponseHeaders(methodResponse));
						Map<String, String> responseModels = methodResponse.getResponseModels();
						if (responseModels != null && !responseModels.isEmpty()) {
							String responseModelName = responseModels.get(DEFAULT_CONTENT_TYPE);
							if (responseModelName == null) {
								responseModelName = responseModels.values().iterator().next();
							} else {
								producesDefaultContentType = true;
							}
							response.setDescription(responseModelName);
							response.setSchema(getResponseSchema(responseModelName, inlineResponseSchema, swagger, modelRefCount));
							updateOperationConsumesProduces(ConsumesProducesEnum.PRODUCES, operation, responseModels.keySet());
						}
						operation.addResponse(methodResponse.getStatusCode(), response);
					}					
				}
				
				String authType = method.getAuthorizationType();
				if (authType != null) {
					operation.setVendorExtension(EXTENSION_AUTH, Collections.singletonMap("type", authType));
				}

				try {
					Integration integration = method.getMethodIntegration();
					if (integration != null) {
						operation.setVendorExtension(EXTENSION_INTEGRATION, getIntegration(integration, resource.getId()));
					}
				} catch (UnsupportedOperationException e) {
					//Ignore
				}
				
				Boolean apiKeyRequired = method.getApiKeyRequired();
				if (apiKeyRequired != null && apiKeyRequired.booleanValue()) {
					operation.addSecurity(SECURITY_API_KEY, Collections.<String>emptyList());
					addApiKey = true;
				}

				List<String> operationProduces = operation.getProduces();
				if (operationProduces != null && !operationProduces.isEmpty()) {
					if (producesContentType == null) {
						producesContentType = operationProduces;
					} else {
						producesContentType.retainAll(operationProduces);
					}
				}
				List<String> operationConsumes = operation.getConsumes();
				if (operationConsumes != null && !operationConsumes.isEmpty()) {
					if (consumesContentType == null) {
						consumesContentType = operationConsumes;
					} else {
						consumesContentType.retainAll(operationConsumes);
					}
				}
				
				path.set(method.getHttpMethod().toLowerCase(), operation);
			}
			String resourcePath = resource.getPath();
			paths.put(resourcePath.substring(basePath.length()), path);
		}
		if (addApiKey) {
			swagger.addSecurityDefinition(SECURITY_API_KEY, 
					new ApiKeyAuthDefinition().name("x-api-key").in(In.HEADER));
		}
		swagger.setPaths(paths);
		if (producesDefaultContentType) {
			swagger.addProduces(DEFAULT_CONTENT_TYPE);
		}
		if (producesContentType != null) {
			for (String contentType : producesContentType) {
				swagger.addProduces(contentType);
			}
		}
		if (consumesDefaultContentType) {
			swagger.addConsumes(DEFAULT_CONTENT_TYPE);
		}
		if (consumesContentType != null) {
			for (String contentType : consumesContentType) {
				swagger.addConsumes(contentType);
			}
		}
		
		if ("yaml".equals(format)) {
			return Yaml.pretty().writeValueAsString(swagger); 
		} else if ("json".equals(format)) {
			return Json.pretty().writeValueAsString(swagger); 
		} else {
			throw new InvalidArgumentException("Unsupported output format: " + format);
		}
	}

	private static void updateOperationConsumesProduces(ConsumesProducesEnum consumesProduces, Operation operation, Collection<String> contentTypes) {
		Set<String> result = new HashSet<String>(contentTypes);
		result.remove(DEFAULT_CONTENT_TYPE);
		if (!result.isEmpty()) {
			List<String> oldValue = consumesProduces == ConsumesProducesEnum.CONSUMES 
					? operation.getConsumes() : operation.getProduces();
			@SuppressWarnings("unchecked")
			Set<String> oldValueSet = new LinkedHashSet<String>(oldValue != null ? oldValue : Collections.EMPTY_LIST);
			oldValueSet.addAll(result);
			if (consumesProduces == ConsumesProducesEnum.CONSUMES) {
				operation.setConsumes(new ArrayList<String>(oldValueSet));
			} else {
				operation.setProduces(new ArrayList<String>(oldValueSet));
			}
		}
	}

	private static Property getResponseSchema(String responseModelName, boolean inlineResponseSchema,
			Swagger swagger, Map<String, Integer> modelRefCount) {
		Property schema = new RefProperty(responseModelName);
		if (inlineResponseSchema && !modelRefCount.containsKey(responseModelName)) {
			com.wordnik.swagger.models.Model model = swagger.getDefinitions().get(responseModelName);
			//If empty model was generated for response type
			if (model instanceof ModelImpl) {
				Map<String, Property> modelProperties = ((ModelImpl) model).getProperties();
				if (modelProperties == null || modelProperties.isEmpty()) {
					String type = ((ModelImpl) model).getType();
					if (type != null) {
						Property property = PropertyBuilder.build(type, ((ModelImpl) model).getFormat(), null);
						if (property != null) {
							schema = property;
							swagger.getDefinitions().remove(responseModelName);
						}
					}
				}
			}
		}
		return schema;
	}

	private static Map<String, Property> getResponseHeaders(MethodResponse methodResponse) {
		Map<String, Property> result = null;
		Map<String, Boolean> responseParameters = methodResponse.getResponseParameters();
		for (Map.Entry<String, Boolean> responseParameterEntry : responseParameters.entrySet()) {
			String parameterName = responseParameterEntry.getKey();
			if (parameterName.startsWith(METHOD_RESPONSE_HEADER)) {
				StringProperty headerProperty = new StringProperty();
				headerProperty.setRequired(responseParameterEntry.getValue());
				if (result == null) {
					result = new LinkedHashMap<String, Property>();
				}
				result.put(parameterName.substring(METHOD_RESPONSE_HEADER.length()), headerProperty);
			} else {
				throw new UnsupportedOperationException("Unsupported response parameter type " + parameterName);
			}
		}
		return result;
	}

	private static BodyParameter getBodyParameter(String requestModelName, boolean inlineBodyParameterSchema, 
			Swagger swagger, Map<String, Integer> modelRefCount) {
		BodyParameter parameter = new BodyParameter().name("body").description(requestModelName);
		if (inlineBodyParameterSchema && !modelRefCount.containsKey(requestModelName)) {
			parameter.setSchema(swagger.getDefinitions().remove(requestModelName));
		} else {
			parameter.setSchema(new RefModel(requestModelName));
		}
		return parameter;
	}

	private static Map<String, Object> getIntegration(Integration integration, String defaultCacheNamespace) {
		Map<String, Object> integrationMap = new HashMap<String, Object>();
		putIfNotNullOrEmpty(integrationMap, "type", integration.getType());
		putIfNotNullOrEmpty(integrationMap, "uri", integration.getUri());
		putIfNotNullOrEmpty(integrationMap, "httpMethod", integration.getHttpMethod());
		putIfNotNullOrEmpty(integrationMap, "credentials", integration.getCredentials());
		String cacheNamespace = integration.getCacheNamespace();
		if (cacheNamespace != null && !cacheNamespace.equals(defaultCacheNamespace)) {
			putIfNotNullOrEmpty(integrationMap, "cacheNamespace", cacheNamespace);
		}
		putIfNotNullOrEmpty(integrationMap, "cacheKeyParameters",  integration.getCacheKeyParameters());
		putIfNotNullOrEmpty(integrationMap, "requestTemplates", integration.getRequestTemplates());
		putIfNotNullOrEmpty(integrationMap, "requestParameters",  integration.getRequestParameters());
		
		Map<String, IntegrationResponse> integrationResponses = integration.getIntegrationResponses();
		if (integrationResponses != null && !integrationResponses.isEmpty()) {
			Map<String, Object> responsesMap = new HashMap<String, Object>();
			for (IntegrationResponse integrationResponse : integrationResponses.values()) {
				String pattern = integrationResponse.getSelectionPattern();
				if (pattern == null) {
					pattern = "default";
				}
				Map<String, Object> map = new HashMap<String, Object>();
				putIfNotNullOrEmpty(map, "statusCode",  integrationResponse.getStatusCode());
				putIfNotNullOrEmpty(map, "responseParameters",  integrationResponse.getResponseParameters());
				putIfNotNullOrEmpty(map, "responseTemplates", integrationResponse.getResponseTemplates());
				putIfNotNullOrEmpty(responsesMap, pattern, map);
			}
			putIfNotNullOrEmpty(integrationMap, "responses", responsesMap);
		}
		return integrationMap;
	}

	private static Parameter getParameter(Map.Entry<String, Boolean> parameterEntry) {
		Parameter parameter;
		String parameterName = parameterEntry.getKey();
		if (parameterName.startsWith(METHOD_REQUEST_PATH)) {
			parameter = new PathParameter()
					.name(parameterName.substring(METHOD_REQUEST_PATH.length()))
					.type("string"); 	//There is no type information for params in API gateway
		} else if (parameterName.startsWith(METHOD_REQUEST_QUERYSTRING)) {
			parameter = new QueryParameter() 
					.name(parameterName.substring(METHOD_REQUEST_QUERYSTRING.length()))
					.type("string");
		} else if (parameterName.startsWith(METHOD_REQUEST_HEADER)) {
			parameter = new HeaderParameter()
					.name(parameterName.substring(METHOD_REQUEST_HEADER.length()))
					.type("string");
		} else {
			throw new UnsupportedOperationException("Unsupported request parameter type " + parameterName);
		}
		parameter.setRequired(parameterEntry.getValue());
		return parameter;
	}

	private static String getBasePath(RestApi restApi) {
		String basePath = null;
		for (Resource resource : restApi.getResources().getItem()) {
			Map<String, Method> resourceMethods = resource.getResourceMethods();
			if (resourceMethods.isEmpty()) {
				continue;
			}
			String resourcePath = resource.getPath();
			if (basePath == null) {
				basePath = resourcePath;
			} else {
				int i=0;
				for (; i<basePath.length() && i<resourcePath.length(); i++) {
					if (basePath.charAt(i) != resourcePath.charAt(i)) {
						break;
					}
				}
				basePath = basePath.substring(0, i);
			}
		}
		if (basePath != null && basePath.endsWith("/")) {
			basePath = basePath.substring(0, basePath.length()-1);
		}
		return basePath;
	}

	private static Map<String, com.wordnik.swagger.models.Model> getDefinitions(RestApi restApi)
			throws IOException, JsonParseException, JsonMappingException {
		Models models = restApi.getModels();
		Map<String, com.wordnik.swagger.models.Model> result = new HashMap<String, com.wordnik.swagger.models.Model>();
		for (Model modelItem : models.getItem()) {
			com.wordnik.swagger.models.Model model = 
					Json.mapper().readValue(modelItem.getSchema(), com.wordnik.swagger.models.Model.class);
			if (model instanceof ModelImpl) {
				((ModelImpl) model).setName(modelItem.getName());
			}
			model.setDescription(modelItem.getDescription());
			result.put(modelItem.getName(), model);
		}
		return result;
	}

	@SuppressWarnings("rawtypes")
	private static void putIfNotNullOrEmpty(Map<String, Object> map, String key, Object value) {
		if (value == null) {
			return;
		}
		if (value instanceof Collection && ((Collection) value).isEmpty()) {
			return;
		}
		if (value instanceof Map) {
			if (((Map) value).isEmpty()) {
				return;
			}
			Map<String, Object> copy = new HashMap<String, Object>();
			//Workaround for com.amazonaws.hal.client.ConvertingMap with null values
			for (Object mapKey : ((Map) value).keySet()) {
				Object mapValue = ((Map) value).get(mapKey);
				if (mapValue == null) {
					mapValue = "";
				}
				copy.put(mapKey.toString(), mapValue);
			}
			value = copy;
		}
		map.put(key, value);
	}

	private static void processModelRefs(Collection<? extends com.wordnik.swagger.models.Model> models, Map<String, Integer> modelRefCount) {
		if (models != null) {
			for (com.wordnik.swagger.models.Model model : models) {
				processModelRefs(model, modelRefCount);
			}
		}
	}
	
	private static void processModelRefs(com.wordnik.swagger.models.Model model, Map<String, Integer> modelRefCount) {
		if (model instanceof RefModel) {
			String ref = ((RefModel) model).getSimpleRef();
			Integer count = modelRefCount.get(ref);
			modelRefCount.put(ref, count != null ? (count + 1) : 1);
		} else if (model instanceof ModelImpl) {
			processPropertyModelRefs(((ModelImpl) model).getProperties(), modelRefCount);
			processPropertyModelRefs(((ModelImpl) model).getAdditionalProperties(), modelRefCount);
		} else if (model instanceof ArrayModel) {
			processPropertyModelRefs(((ArrayModel) model).getProperties(), modelRefCount);
			processPropertyModelRefs(((ArrayModel) model).getItems(), modelRefCount);
		} else if (model instanceof ComposedModel) {
			processPropertyModelRefs(((ComposedModel) model).getProperties(), modelRefCount);
			processModelRefs(((ComposedModel) model).getChild(), modelRefCount);
			processModelRefs(((ComposedModel) model).getParent(), modelRefCount);
			processModelRefs(((ComposedModel) model).getAllOf(), modelRefCount);
			processModelRefs(((ComposedModel) model).getInterfaces(), modelRefCount);
		}
		
	}

	private static void processPropertyModelRefs(Map<String, Property> properties, Map<String, Integer> modelRefCount) {
		if (properties != null) {
			for (Property property : properties.values()) {
				processPropertyModelRefs(property, modelRefCount);
			}
		}
	}
	
	private static void processPropertyModelRefs(Property property, Map<String, Integer> modelRefCount) {
		if (property instanceof RefProperty) {
			String ref = ((RefProperty) property).getSimpleRef();
			Integer count = modelRefCount.get(ref);
			modelRefCount.put(ref, count != null ? (count + 1) : 1);
		} else if (property instanceof ArrayProperty) {
			processPropertyModelRefs(((ArrayProperty) property).getItems(), modelRefCount);
		} else if (property instanceof MapProperty) {
			processPropertyModelRefs(((MapProperty) property).getAdditionalProperties(), modelRefCount);
		}
	}

	private static String getEndpoint(String region) {
        return String.format("https://apigateway.%s.amazonaws.com", region);
    }
}

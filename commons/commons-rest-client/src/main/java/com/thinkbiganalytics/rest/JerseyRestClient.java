package com.thinkbiganalytics.rest;

/*-
 * #%L
 * thinkbig-commons-rest-client
 * %%
 * Copyright (C) 2017 ThinkBig Analytics
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.ByteStreams;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.LayeredConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.DefaultHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.glassfish.jersey.SslConfigurator;
import org.glassfish.jersey.apache.connector.ApacheClientProperties;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.client.HttpUrlConnectorProvider;
import org.glassfish.jersey.client.JerseyClientBuilder;
import org.glassfish.jersey.client.authentication.HttpAuthenticationFeature;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.media.multipart.Boundary;
import org.glassfish.jersey.media.multipart.MultiPart;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.media.multipart.file.StreamDataBodyPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.Future;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.ws.rs.NotAcceptableException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Form;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;
import javax.ws.rs.core.HttpHeaders;

/**
 * Generic JerseyRestClient
 */
public class JerseyRestClient {

    public static final String HOST_NOT_SET_VALUE = "NOT_SET";
    protected static final Logger log = LoggerFactory.getLogger(JerseyRestClient.class);
    /**
     * Flag to indicate if the client is configured correctly and available to be used.
     */
    private boolean isHostConfigured;
    /**
     * The Jersey Client
     */
    protected Client client;
    /**
     * the base uri to connect to set by the configuration of the REST Client.
     * This constructor will set this value using the  {@link JerseyClientConfig#getUrl()}
     * Users of this client can then reference the uri from this client class.
     *
     * @see #JerseyRestClient(JerseyClientConfig)
     */
    protected String uri;
    /**
     * The username to use to connect set by configuration of the REST Client
     * The constructor will set this value using {@link JerseyClientConfig#username}
     * Users of this client can then reference the username from this client class.
     *
     * @see #JerseyRestClient(JerseyClientConfig)
     */
    private String username;
    /**
     * if the supplied endpoint doesnt accept JSON but rather Plain Text, this mapper will be used to resolve the text and turn it into JSON/object
     */
    private ObjectMapper objectMapper;

    /**
     * flag to use the PoolingHttpClientConnectionManager from Apache instead of the Jersey Manager The PoolingHttpClientConnectionManager doesnt support some JSON header which is why this is turned
     * off by default.
     */
    private boolean useConnectionPooling = false;


    private final String USR_USERNAME = "administrator";
    private final String USR_PASSWORD = "password";
    private final String USR_PROCESS_GROUP_ID = "61634ac2-0165-1000-4579-b394588f7d9b";
    private final String USR_ACCESS_TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhZG1pbmlzdHJhdG9yQEtZTE8uSU8iLCJpc3MiOiJLZXJiZXJvc1Byb3ZpZGVyIiwiYXVkIjoiS2VyYmVyb3NQcm92aWRlciIsInByZWZlcnJlZF91c2VybmFtZSI6ImFkbWluaXN0cmF0b3JAS1lMTy5JTyIsImtpZCI6MywiZXhwIjoxNTM1NzU4OTU1LCJpYXQiOjE1MzU3MTU3NTV9.JScS-tXoHNlYGyKw0tUwVzVbTcClO9hTI8_6RbW2vTk";

    public JerseyRestClient(JerseyClientConfig config) {
        useConnectionPooling = config.isUseConnectionPooling();
        SSLContext sslContext = null;
        if (config.isHttps()) {
            SslConfigurator sslConfig = null;
            byte[] keyStoreFile = null;
            byte[] truststoreFile = null;

            if (StringUtils.isNotBlank(config.getKeystorePath())) {
                try (InputStream keystore = JerseyRestClient.class.getResourceAsStream(config.getKeystorePath())) {

                    if (keystore != null) {
                        keyStoreFile = ByteStreams.toByteArray(keystore);
                    }

                } catch (IOException e) {
                    log.error("Encountered IOException attempting to get keystore: ", e);
                }
            }

            if (StringUtils.isNotBlank(config.getTruststorePath())) {
                try (InputStream truststore = JerseyRestClient.class.getResourceAsStream(config.getTruststorePath())) {
                    if (truststore != null) {
                        truststoreFile = ByteStreams.toByteArray(truststore);
                    }

                } catch (IOException e) {
                    log.error("Encountered IOException attempting to get keystore: ", e);
                }
            }

            if (config.getTruststorePassword() == null && config.getKeystorePassword() == null) {
                log.warn("keystorePassword and truststorePassword should not both be null, check application.properties");
            }

            if (keyStoreFile != null) {
                sslConfig = SslConfigurator.newInstance()
                        .trustStoreBytes(truststoreFile != null ? truststoreFile : keyStoreFile)
                        .trustStorePassword(config.getTruststorePassword() != null ? String.valueOf(config.getTruststorePassword()) : String.valueOf(config.getKeystorePassword()))
                        .trustStoreType(config.getTrustStoreType())
                        .keyStoreBytes(keyStoreFile != null ? keyStoreFile : truststoreFile)
                        .keyStorePassword(config.getKeystorePassword());
            } else {
                sslConfig = SslConfigurator.newInstance()
                        .keyStoreFile(config.getKeystorePath() == null ? config.getTruststorePath() : config.getKeystorePath())
                        .keyStorePassword(config.getKeystorePassword() == null ? config.getTruststorePassword() : config.getKeystorePassword())
                        .trustStoreFile(config.getTruststorePath() == null ? config.getKeystorePath() : config.getTruststorePath())
                        .trustStorePassword(config.getTruststorePassword() == null ? String.valueOf(config.getKeystorePassword()) : String.valueOf(config.getTruststorePassword()))
                        .trustStoreType(config.getTrustStoreType());
            }

            try {
                sslContext = sslConfig.createSSLContext();
            } catch (Exception e) {
                log.error("ERROR creating CLient SSL Context.  " + e.getMessage() + " Falling back to Jersey Client without SSL.  Rest Integration with " + config.getUrl() + "  will probably not work until this is fixed!");
            }
        }

        TrustManager[] trustAllCerts = new TrustManager[] {
                new X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                    public void checkClientTrusted(
                            java.security.cert.X509Certificate[] certs, String authType) {
                    }
                    public void checkServerTrusted(
                            java.security.cert.X509Certificate[] certs, String authType) {
                    }
                }
        };
        try {
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
        } catch(Exception e) {
            log.error("ERROR INIT SSL: " + e.getMessage());
        }
        

        ClientConfig clientConfig = new ClientConfig();

        // Add in Timeouts if configured.  Values are in milliseconds
        if (config.getReadTimeout() != null) {
            clientConfig.property(ClientProperties.READ_TIMEOUT, config.getReadTimeout());
        }
        if (config.getConnectTimeout() != null) {
            clientConfig.property(ClientProperties.CONNECT_TIMEOUT, config.getConnectTimeout());
        }

        if (useConnectionPooling) {

            PoolingHttpClientConnectionManager connectionManager = null;
            if (sslContext != null) {

                HostnameVerifier defaultHostnameVerifier = new DefaultHostnameVerifier();

                LayeredConnectionSocketFactory sslSocketFactory = new SSLConnectionSocketFactory(
                        sslContext,
                        defaultHostnameVerifier);

                final Registry<ConnectionSocketFactory> registry = RegistryBuilder.<ConnectionSocketFactory>create()
                        .register("http", PlainConnectionSocketFactory.getSocketFactory())
                        .register("https", sslSocketFactory)
                        .build();

                connectionManager = new PoolingHttpClientConnectionManager(registry);
            } else {
                connectionManager = new PoolingHttpClientConnectionManager();
            }
            connectionManager.setDefaultMaxPerRoute(100); // # of connections allowed per host/address
            connectionManager.setMaxTotal(200); // number of connections allowed in total

            clientConfig.property(ApacheClientProperties.CONNECTION_MANAGER, connectionManager);
            HttpUrlConnectorProvider connectorProvider = new HttpUrlConnectorProvider();
            clientConfig.connectorProvider(connectorProvider);

        }

        clientConfig.register(MultiPartFeature.class);

        // allow derived classes to modify the client config
        extendClientConfig(clientConfig);

        if (sslContext != null) {
            log.info("Created new Jersey Client with SSL connecting to {} ", config.getUrl());
            client = new JerseyClientBuilder().withConfig(clientConfig).sslContext(sslContext).build();
        } else {
            log.info("Created new Jersey Client without SSL connecting to {} ", config.getUrl());
            client = JerseyClientBuilder.createClient(clientConfig);
        }

        log.info("DEV: Client SSL is: " + client.toString());

        // Register Jackson for the internal mapper
        objectMapper = new JacksonObjectMapperProvider().getContext(null);
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        //register custom features
        registerClientFeatures(client);

        // Configure authentication
        if (StringUtils.isNotBlank(config.getUsername())) {
            HttpAuthenticationFeature feature = HttpAuthenticationFeature.basic(config.getUsername(), String.valueOf(config.getPassword()));
            client.register(feature);
        }
        this.uri = config.getUrl();
        this.username = config.getUsername();

        if (StringUtils.isNotBlank(config.getHost()) && !HOST_NOT_SET_VALUE.equals(config.getHost())) {
            this.isHostConfigured = true;
        } else {
            log.info("Jersey Rest Client not initialized.  Host name is Not set!!");
        }
    }

    /**
     * Allows custom clients to override and register custom features to the client.
     * Default does standard Jackson JSON mapping
     *
     * @param client the Rest Client
     */
    protected void registerClientFeatures(Client client) {
        client.register(JacksonObjectMapperProvider.class);
        client.register(JacksonFeature.class);
    }


    /**
     * Allows derived classes to make modifications to the clientConfig before it is used to construct the client.
     *
     * @param clientConfig the Rest Client Configuration
     *                     NOTE:  this method should never be final.  Some plugin classes may extend this method.
     */
    protected void extendClientConfig(ClientConfig clientConfig) {

    }

    /**
     * Flag to detect if this client is configured correctly.
     *
     * @return true if configured correctly, false if not.
     */
    public boolean isHostConfigured() {
        return isHostConfigured;
    }


    /**
     * Get the username connecting to this REST service
     *
     * @return the user connecting
     */
    public String getUsername() {
        return username;
    }


    /**
     * The base target that will be used upon each request.
     * All rest calls will go through this method.
     * Specific clients that extend this class can override this method to specify a given root path.
     *
     * @return the target to use to make th REST request
     */
    protected WebTarget getBaseTarget() {
        WebTarget target = client.target(uri);
        return target;
    }

    /**
     * prepends the supplied {@link #uri} to the supplied path
     *
     * @param path the path to append to the {@link #uri}
     * @return the target to use to make the REST request
     */
    protected WebTarget getTargetFromPath(String path) {
        String updatedPath = uri + path;
        WebTarget target = client.target(updatedPath);
        return target;
    }


    /**
     * Build a target adding the supplied query parameters to the the request
     *
     * @param path   the path to access
     * @param params the key,value parameters to add to the request
     * @return the target to use to make the REST request
     */
    private WebTarget buildTarget(String path, Map<String, Object> params) {
        WebTarget target = getBaseTarget().path(path.replace("root", USR_PROCESS_GROUP_ID));
        if (params != null) {
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                target = target.queryParam(entry.getKey(), entry.getValue());
                log.info("DEV: query param: " + entry.getKey() + ", " + entry.getValue());
            }
        }
        log.info("DEV: Request web target is: " + target.toString());

        return target;
    }

    /**
     * Begins a request for a specified path and parameters.
     *
     * @param path   the request path
     * @param params the request parameters
     * @return a builder with which to continue constructing the request
     */
    public Invocation.Builder request(String path, Map<String, Object> params) {
        return buildTarget(path, params).request();
    }

    /**
     * Builds a target for for a specified path and parameters.
     *
     * @param path   the request path
     * @param params the request parameters
     * @return a WebTarget instance
     */
    public WebTarget target(String path, Map<String, Object> params) {
        return buildTarget(path, params);
    }

    /**
     * Perform a asynchronous GET request
     *
     * @param path   the path to access
     * @param params the key,value parameters to add to the request
     * @param clazz  the returned class type
     * @return a Future of type T
     */
    public <T> Future<T> getAsync(String path, Map<String, Object> params, Class<T> clazz) {
        WebTarget target = buildTarget(path, params);
        return target.request(MediaType.APPLICATION_JSON_TYPE)
        .accept(MediaType.APPLICATION_JSON_TYPE)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + USR_ACCESS_TOKEN)
        .async()
        .get(clazz);
    }


    /**
     * Perform a asynchronous GET request
     *
     * @param path   the path to access
     * @param params the parameters to add to the request
     * @param type   the returned class type
     * @return a Future of type T
     */
    public <T> Future<T> getAsync(String path, Map<String, Object> params, GenericType<T> type) {
        WebTarget target = buildTarget(path, params);
        return target.request(MediaType.APPLICATION_JSON_TYPE)
        .accept(MediaType.APPLICATION_JSON_TYPE)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + USR_ACCESS_TOKEN)
        .async()
        .get(type);

    }

    /**
     * Perform a GET request using the current state of the supplied builder.
     *
     * @param builder the Invokation.Builder
     * @param clazz   the type of result
     * @param <T>     the returned class type
     * @return the returned object of the specified Class
     */
    public <T> T get(Invocation.Builder builder, Class<T> clazz) {
        return get(builder, clazz, true);
    }

    /**
     * Perform a GET request against the supplied web target.
     *
     * @param target the WebTarget
     * @param clazz  the type of result
     * @param <T>    the returned class type
     * @return the returned object of the specified Class
     */
    public <T> T get(WebTarget target, Class<T> clazz) {
        return get(target, clazz, true);
    }

    /**
     * Perform a GET request using the current state of the supplied builder.
     *
     * @param builder  the Invokation.Builder
     * @param clazz    the type of result
     * @param <T>      the returned class type
     * @param logError true to log an exceptions, false to not
     * @return the returned object of the specified Class
     */
    public <T> T get(Invocation.Builder builder, Class<T> clazz, boolean logError) {
        T obj = null;

        try {
            obj = builder.accept(MediaType.APPLICATION_JSON_TYPE, MediaType.APPLICATION_XML_TYPE)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + USR_ACCESS_TOKEN)
            .get(clazz);
        } catch (Exception e) {
            if (e instanceof NotAcceptableException) {
                obj = handleNotAcceptableGetRequestJsonException(builder, clazz);
            } else {
                if (logError) {
                    log.error("Failed to process request " + builder, e);
                }
            }
        }
        return obj;
    }

    /**
     * Perform a GET request against the supplied web target.
     *
     * @param builder  the WebTarget
     * @param clazz    the type of result
     * @param <T>      the returned class type
     * @param logError true to log an exceptions, false to not
     * @return the returned object of the specified Class
     */
    public <T> T get(WebTarget target, Class<T> clazz, boolean logError) {
        return get(target.request(MediaType.APPLICATION_JSON_TYPE).header(HttpHeaders.AUTHORIZATION, "Bearer " + USR_ACCESS_TOKEN), clazz, logError);
    }

    /**
     * Perform a GET request
     *
     * @param path   the path to access
     * @param params key, value parameters to add to the request
     * @param clazz  the class type to return as the response from the GET request
     * @param <T>    the returned class type
     * @return the returned object of the specified Class
     */
    public <T> T get(String path, Map<String, Object> params, Class<T> clazz) {
        return get(path, params, clazz, true);
    }

    /**
     * Perform a GET request.  Exceptions will not be logged
     *
     * @param path     the path to access
     * @param params   key, value parameters to add to the request
     * @param clazz    the class type to return as the response from the GET request
     * @param logError true to log an exceptions, false to not
     * @param <T>      the returned class type
     * @return the returned object of the specified Class
     */
    public <T> T get(String path, Map<String, Object> params, Class<T> clazz, boolean logError) {
        WebTarget target = buildTarget(path, params);
        return get(target, clazz, logError);
    }

    /**
     * call a GET request
     *
     * @param path    the path to call.
     * @param headers key, list parameters to add http request headers to the request
     * @param clazz   the class type to return as the response from the GET request
     * @param params  key,value parameters to add to the request
     * @param <T>     the class to return
     * @return the response of class type T
     */
    public <T> T getWithHeaders(String path, MultivaluedMap<String, Object> headers, Map<String, Object> params, Class<T> clazz) {
        WebTarget target = buildTarget(path, params);

        Invocation.Builder builder = target.request(MediaType.APPLICATION_JSON_TYPE)
                .headers(headers)
                .accept(MediaType.APPLICATION_JSON_TYPE, MediaType.APPLICATION_XML_TYPE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + USR_ACCESS_TOKEN);

        return builder.get(clazz);
    }


    /**
     * Perform a GET request.  if it returns an exception the message will not be logged.
     *
     * @param path   the path to access
     * @param params key, value parameters to add to the request
     * @param clazz  the class type to return as the response from the GET request
     * @param <T>    the returned class type
     * @return the returned object of the specified Class
     */
    public <T> T getWithoutErrorLogging(String path, Map<String, Object> params, Class<T> clazz) {
        WebTarget target = buildTarget(path, params);
        T obj = null;

        try {
            obj = target.request(MediaType.APPLICATION_JSON_TYPE)
            .accept(MediaType.APPLICATION_JSON_TYPE, MediaType.APPLICATION_XML_TYPE)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + USR_ACCESS_TOKEN)
            .get(clazz);
        } catch (Exception e) {
            if (e instanceof NotAcceptableException) {
                obj = handleNotAcceptableGetRequestJsonException(target, clazz);
            }
        }
        return obj;
    }


    /**
     * Sometimes QueryParams dont fit the model of key,value pairs.
     * This method can be used to call a GET request using the path passed in
     * Allow a client to create the target passing in a full url path with ? and & query params
     * If you have known key,value pairs its recommend you use the {@link #get(String, Map, Class)}
     *
     * @param path  the path to access, including the root target and the ?key=value&key2=value&key3
     * @param clazz the class type to return as the response from the GET request
     * @param <T>   the returned class type
     * @return the returned object of the specified Class
     */
    public <T> T getFromPathString(String path, Class<T> clazz) {

        WebTarget target = getTargetFromPath(path);

        T obj = null;
        try {

            obj = target.request(MediaType.APPLICATION_JSON_TYPE)
            .accept(MediaType.APPLICATION_JSON_TYPE, MediaType.APPLICATION_XML_TYPE)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + USR_ACCESS_TOKEN)
            .get(clazz);
        } catch (Exception e) {
            if (e instanceof NotAcceptableException) {
                obj = handleNotAcceptableGetRequestJsonException(target, clazz);
            } else {
                log.error("Failed to process request " + path, e);
            }
        }
        return obj;
    }

    /**
     * Makes a GET request and doesn"t handle errors on purpose.
     */
    public <T> T get(String path, Class<T> clazz) {
        WebTarget target = getTargetFromPath(path);
        return target.request(MediaType.APPLICATION_JSON_TYPE)
        .accept(MediaType.APPLICATION_JSON_TYPE, MediaType.APPLICATION_XML_TYPE)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + USR_ACCESS_TOKEN)
        .get(clazz);
    }


    /**
     * call a GET request
     *
     * @param path   the path to call.
     * @param params key, value parameters to add to the request
     * @param type   the GenericType of Class T
     * @param <T>    the class to return
     * @return the response of class type T
     */
    public <T> T get(String path, Map<String, Object> params, GenericType<T> type) {
        WebTarget target = buildTarget(path, params);
        return target.request(MediaType.APPLICATION_JSON_TYPE).accept(MediaType.APPLICATION_JSON_TYPE, MediaType.APPLICATION_XML_TYPE).get(type);
    }

    /**
     * POST an object to a given url
     *
     * @param path the path to access
     * @param o    the object to post
     * @return the response
     */
    public Response post(String path, Object o) {
        WebTarget target = buildTarget(path, null);
        return target.request()
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + USR_ACCESS_TOKEN)
        .post(Entity.entity(o, MediaType.APPLICATION_JSON_TYPE));
    }


    /**
     * POST an object with multiplepart.  For example Uploading a file.
     * Below is a sample on how to create a Multipart from a string and post it
     * String xml = "some  string";
     * final FormDataBodyPart templatePart = new FormDataBodyPart("template", xml, MediaType.APPLICATION_OCTET_STREAM_TYPE);
     * <p>
     * FormDataContentDisposition.FormDataContentDispositionBuilder disposition = FormDataContentDisposition.name(templatePart.getName());
     * disposition.fileName("fileName");
     * templatePart.setFormDataContentDisposition(disposition.build());
     * // Combine parts
     * MultiPart multiPart = new MultiPart();
     * multiPart.bodyPart(templatePart);
     * multiPart.setMediaType(MediaType.MULTIPART_FORM_DATA_TYPE);
     *
     * @param path       the path to access
     * @param object     the multiplart object to post
     * @param returnType the type to return
     * @return returns the response of the type T
     */
    public <T> T postMultiPart(String path, MultiPart object, Class<T> returnType) {
        WebTarget target = buildTarget(path, null);
        MediaType contentType = MediaType.MULTIPART_FORM_DATA_TYPE;
        contentType = Boundary.addBoundary(contentType);
        return target.request()
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + USR_ACCESS_TOKEN)
        .post(Entity.entity(object, contentType), returnType);
    }

    /**
     * POST a multipart object streaming
     *
     * @param path       the path to access
     * @param name       the name of the param the endpoint is expecting
     * @param fileName   the name of the file
     * @param stream     the stream itself
     * @param returnType the type to return from the post
     * @return the response of type T
     */
    public <T> T postMultiPartStream(String path, String name, String fileName, InputStream stream, Class<T> returnType) {
        WebTarget target = buildTarget(path, null);
        MultiPart multiPart = new MultiPart(MediaType.MULTIPART_FORM_DATA_TYPE);

        StreamDataBodyPart streamDataBodyPart = new StreamDataBodyPart(name, stream, fileName, MediaType.APPLICATION_OCTET_STREAM_TYPE);
        multiPart.getBodyParts().add(streamDataBodyPart);
        MediaType contentType = MediaType.MULTIPART_FORM_DATA_TYPE;
        contentType = Boundary.addBoundary(contentType);
        return target.request()
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + USR_ACCESS_TOKEN)
        .post(
                Entity.entity(multiPart, contentType), returnType);
    }


    /**
     * POST an object
     *
     * @param path       the path to access
     * @param object     the object to post
     * @param returnType the class to return
     * @return the response of type T
     */
    public <T> T post(String path, Object object, Class<T> returnType) {
        WebTarget target = buildTarget(path, null);
        return target.request()
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + USR_ACCESS_TOKEN)
        .post(Entity.entity(object, MediaType.APPLICATION_JSON), returnType);
    }

    /**
     * PUT request
     *
     * @param path       the path to access
     * @param object     the object to PUT
     * @param returnType the class to return
     * @return the response of type T
     */
    public <T> T put(String path, Object object, Class<T> returnType) {
        WebTarget target = buildTarget(path, null);
        return target.request()
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + USR_ACCESS_TOKEN)
        .put(Entity.entity(object, MediaType.APPLICATION_JSON), returnType);
    }

    /**
     * DELETE request
     *
     * @param path       the path to access
     * @param params     Any additional Query params to add to the DELETE call
     * @param returnType the class to return
     * @return the response of type T
     */
    public <T> T delete(String path, Map<String, Object> params, Class<T> returnType) {
        WebTarget target = buildTarget(path, params);
        return target.request()
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + USR_ACCESS_TOKEN)
        .delete(returnType);
    }


    /**
     * call a GET request
     *
     * @param path    the path to call.
     * @param headers key, list parameters to add http request headers to the request
     * @param clazz   the class type to return as the response from the GET request
     * @param params  key,value parameters to add to the request
     * @param <T>     the class to return
     * @return the response of class type T
     */
    public <T> T deleteWithHeaders(String path, MultivaluedMap<String, Object> headers, Map<String, Object> params, Class<T> clazz) {
        WebTarget target = buildTarget(path, params);

        Invocation.Builder builder = target.request(MediaType.APPLICATION_JSON_TYPE)
                .headers(headers)
                .accept(MediaType.APPLICATION_JSON_TYPE, MediaType.APPLICATION_XML_TYPE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + USR_ACCESS_TOKEN);

        return builder.delete(clazz);
    }

    /**
     * POST a request from a {@link Form} object
     *
     * @param path       the path to access
     * @param form       the Form to POST
     * @param returnType the class to return
     * @return the response of type T
     */
    public <T> T postForm(String path, Form form, Class<T> returnType) {
        WebTarget target = buildTarget(path, null);
        return target.request()
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + USR_ACCESS_TOKEN)
        .post(Entity.entity(form, MediaType.APPLICATION_FORM_URLENCODED_TYPE), returnType);
    }

    /**
     * POST a request Async.
     *
     * @param path       the path to access
     * @param object     the object to POST
     * @param returnType the class to return
     * @return a Future of type T
     */
    public <T> Future<T> postAsync(String path, Object object, Class<T> returnType) {
        WebTarget target = buildTarget(path, null);
        return target.request()
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + USR_ACCESS_TOKEN)
        .async()
        .post(Entity.entity(object, MediaType.APPLICATION_JSON), returnType);
    }

    /**
     * If a request doesn"t like the accepted type (i.e. its coded for TEXT instead of JSON, try to resolve the JSON by getting the JSON string
     * This can be called in the Exception of a particular GET request which will attempt to resolve the correct object from the Response string.
     *
     * @param builder the Invocation.Builder
     * @param clazz   the class to return
     * @return the response of type T
     * @see JerseyRestClient#get(String, Map, Class)
     * @see JerseyRestClient#getFromPathString(String, Class)
     */
    private <T> T handleNotAcceptableGetRequestJsonException(Invocation.Builder builder, Class<T> clazz) {
        T obj = null;
        try {
            //the response didnt link getting data in JSON.. attempt to get it in TEXT and convert to JSON
            String jsonString = builder.accept(MediaType.TEXT_PLAIN_TYPE)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + USR_ACCESS_TOKEN)
            .get(String.class);
            if (StringUtils.isNotBlank(jsonString)) {
                try {
                    obj = objectMapper.readValue(jsonString, clazz);
                } catch (Exception ex) {
                    //unable to deserialize string
                    log.error("Unable to deserialize request to JSON for string {}, for builder {} returning class {} ", jsonString, builder, clazz);
                }
            }
        } catch (Exception ex1) {
            //swallow the exception.  cant do anything about it.
            log.error("Unable to deserialize request for target {} returning class {} ", builder, clazz);
        }
        return obj;
    }

    /**
     * If a request doesn"t like the accepted type (i.e. its coded for TEXT instead of JSON, try to resolve the JSON by getting the JSON string
     * This can be called in the Exception of a particular GET request which will attempt to resolve the correct object from the Response string.
     *
     * @param target the WebTarget
     * @param clazz  the class to return
     * @return the response of type T
     * @see JerseyRestClient#get(String, Map, Class)
     * @see JerseyRestClient#getFromPathString(String, Class)
     */
    private <T> T handleNotAcceptableGetRequestJsonException(WebTarget target, Class<T> clazz) {
        return handleNotAcceptableGetRequestJsonException(target.request(MediaType.APPLICATION_JSON_TYPE).header(HttpHeaders.AUTHORIZATION, "Bearer " + USR_ACCESS_TOKEN), clazz);
    }


}

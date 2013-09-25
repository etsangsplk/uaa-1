/*
 * Copyright 2002-2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cloudfoundry.identity.uaa.test;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.cloudfoundry.identity.uaa.user.UaaUser;
import org.junit.rules.TestWatchman;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.Statement;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJacksonHttpMessageConverter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.DefaultOAuth2ClientContext;
import org.springframework.security.oauth2.client.OAuth2ClientContext;
import org.springframework.security.oauth2.client.OAuth2RestTemplate;
import org.springframework.security.oauth2.client.resource.OAuth2ProtectedResourceDetails;
import org.springframework.security.oauth2.client.token.AccessTokenRequest;
import org.springframework.security.oauth2.client.token.DefaultAccessTokenRequest;
import org.springframework.security.oauth2.provider.BaseClientDetails;
import org.springframework.security.oauth2.provider.ClientDetails;
import org.springframework.util.Assert;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestOperations;

/**
 * @author Dave Syer
 * 
 */
public class TestAccountSetup extends TestWatchman {

	private static Log logger = LogFactory.getLog(TestAccountSetup.class);

	private final UrlHelper serverRunning;

	private final UaaTestAccounts testAccounts;

	private UaaUser user;

	private static boolean initialized = false;

	private TestAccountSetup(UrlHelper serverRunning, UaaTestAccounts testAccounts) {
		this.serverRunning = serverRunning;
		this.testAccounts = testAccounts;
	}

	public static TestAccountSetup standard(UrlHelper serverRunning, UaaTestAccounts testAccounts) {
		return new TestAccountSetup(serverRunning, testAccounts);
	}

	@Override
	public Statement apply(Statement base, FrameworkMethod method, Object target) {
		initializeIfNecessary(method, target);
		return super.apply(base, method, target);
	}

	/**
	 * @return the user (if already created null otherwise)
	 */
	public UaaUser getUser() {
		return user;
	}

	private void initializeIfNecessary(FrameworkMethod method, Object target) {
		OAuth2ProtectedResourceDetails resource = testAccounts.getAdminClientCredentialsResource();
		OAuth2RestTemplate client = createRestTemplate(resource, new DefaultAccessTokenRequest());
		// Cache statically to save time on a test suite
		if (!initialized) {
			logger.info("Checking user account context for server=" + resource.getAccessTokenUri());
			if (!scimClientExists(client)) {
				createScimClient(client);
			}
			if (!appClientExists(client)) {
				createAppClient(client);
			}
			if (!vmcClientExists(client)) {
				createVmcClient(client);
			}
			initialized = true;
		}
		resource = testAccounts.getClientCredentialsResource("oauth.clients.scim", "scim", "scimsecret");
		client = createRestTemplate(resource, new DefaultAccessTokenRequest());
		initializeUserAccount(client);
	}

	private void createVmcClient(RestOperations client) {
		BaseClientDetails clientDetails = new BaseClientDetails("vmc", "cloud_controller,openid,password",
				"openid,cloud_controller.read,password.write,scim.userids", "implicit", "uaa.none",
				"https://uaa.cloudfoundry.com/redirect/vmc");
		createClient(client, testAccounts.getClientDetails("oauth.clients.vmc", clientDetails));
	}

	private void createScimClient(RestOperations client) {
		BaseClientDetails clientDetails = new BaseClientDetails("scim", "none", "uaa.none", "client_credentials",
				"scim.read,scim.write,password.write");
		clientDetails.setClientSecret("scimsecret");
		createClient(client, testAccounts.getClientDetails("oauth.clients.scim", clientDetails));
	}

	private void createAppClient(RestOperations client) {
		BaseClientDetails clientDetails = new BaseClientDetails("app", "none",
				"cloud_controller.read,openid,password.write", "password,authorization_code,refresh_token",
				"uaa.resource");
		clientDetails.setClientSecret("appclientsecret");
		createClient(client, testAccounts.getClientDetails("oauth.clients.app", clientDetails));
	}

	private void createClient(RestOperations client, ClientDetails clientDetails) {
		ResponseEntity<String> response = client.postForEntity(serverRunning.getClientsUri(), clientDetails,
				String.class);
		assertEquals(HttpStatus.CREATED, response.getStatusCode());
	}

	private boolean clientExists(RestOperations client, OAuth2ProtectedResourceDetails resource) {
		ResponseEntity<String> response = client.getForEntity(
				serverRunning.getClientsUri() + "/" + resource.getClientId(), String.class);
		return response != null && response.getStatusCode() == HttpStatus.OK;
	}

	private boolean vmcClientExists(RestOperations client) {
		return clientExists(client, testAccounts.getImplicitResource("oauth.clients.vmc", "vmc", null));
	}

	private boolean scimClientExists(RestOperations client) {
		return clientExists(client,
				testAccounts.getClientCredentialsResource("oauth.clients.scim", "scim", "scimsecret"));
	}

	private boolean appClientExists(RestOperations client) {
		return clientExists(client,
				testAccounts.getClientCredentialsResource("oauth.clients.app", "app", "appclientsecret"));
	}

	private void initializeUserAccount(RestOperations client) {

		if (this.user == null) {

			UaaUser user = testAccounts.getUser();
			@SuppressWarnings("rawtypes")
			ResponseEntity<Map> results = client.getForEntity(serverRunning.getUserUri() + "?filter=userName eq \""
					+ user.getUsername() + "\"", Map.class);
			assertEquals(HttpStatus.OK, results.getStatusCode());
			@SuppressWarnings("unchecked")
			List<Map<String, ?>> resources = (List<Map<String, ?>>) results.getBody().get("resources");
			Map<String, ?> map;
			if (!resources.isEmpty()) {
				map = resources.get(0);
			}
			else {
				map = getUserAsMap(user);
				@SuppressWarnings("rawtypes")
				ResponseEntity<Map> response = client.postForEntity(serverRunning.getUserUri(), map, Map.class);
				Assert.state(response.getStatusCode() == HttpStatus.CREATED, "User account not created: status was "
						+ response.getStatusCode());
				@SuppressWarnings("unchecked")
				Map<String, ?> value = response.getBody();
				map = value;
			}
			this.user = getUserFromMap(map);

		}

	}

	private UaaUser getUserFromMap(Map<String, ?> map) {
		String id = (String) map.get("id");
		String userName = (String) map.get("userName");
		String email = null;
		if (map.containsKey("emails")) {
			@SuppressWarnings("unchecked")
			Collection<Map<String,String>> emails = (Collection<Map<String,String>>)map.get("emails");
			if (!emails.isEmpty()) {
				email = emails.iterator().next().get("value");
			}
		}
		String givenName = null;
		String familyName = null;
		if (map.containsKey("name")) {
			@SuppressWarnings("unchecked")
			Map<String,String> name = (Map<String,String>)map.get("name");
			givenName = name.get("givenName");
			familyName = name.get("familyName");
		}
		@SuppressWarnings("unchecked")
		Collection<Map<String,String>> groups = (Collection<Map<String,String>>)map.get("groups");
		return new UaaUser(id, userName, "<N/A>", email, extractAuthorities(groups), givenName, familyName, new Date(), new Date());
	}

	private List<? extends GrantedAuthority> extractAuthorities(Collection<Map<String, String>> groups) {
		List<SimpleGrantedAuthority> authorities = new ArrayList<SimpleGrantedAuthority>();
		for (Map<String,String> group : groups) {
			String role = group.get("display");
			Assert.state(role!=null, "Role is null in this group: " + group);
			authorities.add(new SimpleGrantedAuthority(role));
		}
		return authorities ;
	}

	private Map<String, ?> getUserAsMap(UaaUser user) {
		HashMap<String, Object> result = new HashMap<String, Object>();
		if (user.getId() != null) {
			result.put("id", user.getId());
		}
		if (user.getUsername() != null) {
			result.put("userName", user.getUsername());
		}
		String email = user.getEmail();
		if (email != null) {
			@SuppressWarnings("unchecked")
			List<Map<String, String>> emails = Arrays.asList(Collections.singletonMap("value", email));
			result.put("emails", emails);
		}
		String givenName = user.getGivenName();
		if (givenName != null) {
			Map<String, String> name = new HashMap<String, String>();
			name.put("givenName", givenName);
			if (user.getFamilyName() != null) {
				name.put("familyName", user.getFamilyName());
			}
			result.put("name", name);
		}
		return result;
	}

	private OAuth2RestTemplate createRestTemplate(OAuth2ProtectedResourceDetails resource,
			AccessTokenRequest accessTokenRequest) {
		OAuth2ClientContext context = new DefaultOAuth2ClientContext(accessTokenRequest);
		OAuth2RestTemplate client = new OAuth2RestTemplate(resource, context);
		client.setRequestFactory(new SimpleClientHttpRequestFactory() {
			@Override
			protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
				super.prepareConnection(connection, httpMethod);
				connection.setInstanceFollowRedirects(false);
			}
		});
		client.setErrorHandler(new ResponseErrorHandler() {
			// Pass errors through in response entity for status code analysis
			@Override
			public boolean hasError(ClientHttpResponse response) throws IOException {
				return false;
			}

			@Override
			public void handleError(ClientHttpResponse response) throws IOException {
			}
		});
		List<HttpMessageConverter<?>> list = new ArrayList<HttpMessageConverter<?>>();
		list.add(new StringHttpMessageConverter());
		list.add(new MappingJacksonHttpMessageConverter());
		client.setMessageConverters(list);
		return client;
	}

}

/*
 * DavMail POP/IMAP/SMTP/CalDav/LDAP Exchange Gateway
 * Copyright (C) 2010  Mickael Guessant
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package davmail.http;

import davmail.AbstractDavMailTestCase;
import davmail.Settings;
import davmail.http.request.GetRequest;
import davmail.http.request.PostRequest;
import davmail.http.request.RestRequest;
import org.apache.http.Consts;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.impl.client.BasicResponseHandler;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

public class TestHttpClientAdapter extends AbstractDavMailTestCase {
    public void testBasicGetRequest() throws IOException {
        try (HttpClientAdapter httpClientAdapter = new HttpClientAdapter("http://davmail.sourceforge.net/version.txt")) {

            HttpGet httpget = new HttpGet("http://davmail.sourceforge.net/version.txt");
            try (CloseableHttpResponse response = httpClientAdapter.execute(httpget)) {
                String responseString = new BasicResponseHandler().handleResponse(response);
                System.out.println(responseString);
            }
        }
    }

    public void testEWSAuthentication() throws IOException {
        String url = Settings.getProperty("davmail.url");

        try (HttpClientAdapter httpClientAdapter = new HttpClientAdapter(url, username, password, true)) {
            HttpGet httpget = new HttpGet(url);
            try (CloseableHttpResponse response = httpClientAdapter.execute(httpget)) {
                assertEquals(HttpStatus.SC_OK, response.getStatusLine().getStatusCode());
                String responseString = new BasicResponseHandler().handleResponse(response);
                System.out.println(responseString);
            }

        }
    }

    public void testGetMicrosoftOnline() throws URISyntaxException, IOException, JSONException {
        // common DavMail client id
        String clientId = Settings.getProperty("davmail.oauth.clientId", "facd6cff-a294-4415-b59f-c5b01937d7bd");
        // standard native app redirectUri
        String redirectUri = Settings.getProperty("davmail.oauth.redirectUri", "https://login.microsoftonline.com/common/oauth2/nativeclient");

        URI uri = new URIBuilder()
                .setScheme("https")
                .setHost("login.microsoftonline.com")
                .setPath("/common/oauth2/authorize")
                .addParameter("client_id", clientId)
                .addParameter("response_type", "code")
                .addParameter("redirect_uri", redirectUri)
                .addParameter("response_mode", "query")
                .addParameter("resource", "https://outlook.office365.com")
                .addParameter("login_hint", username)
                // force consent
                //.addParameter("prompt", "consent")
                .build();

        try (HttpClientAdapter httpClientAdapter = new HttpClientAdapter(uri.toString())) {
            GetRequest getRequest = new GetRequest(uri);
            assertEquals(HttpStatus.SC_OK, getRequest.getStatusCode());
            if ("login.microsoftonline.com".equals(getRequest.getURI().getHost())) {

                System.out.println(getRequest.getResponsePart("Config=([^\n]+);"));
                JSONObject config = new JSONObject(getRequest.getResponsePart("Config=([^\n]+);"));
                assertNotNull(config.getString("sCtx"));

                String context = config.getString("sCtx"); // csts request
                String apiCanary = config.getString("apiCanary"); // canary for API calls
                String clientRequestId = config.getString("correlationId");
                String hpgact = config.getString("hpgact");
                String hpgid = config.getString("hpgid");
                String flowToken = config.getString("sFT");
                String canary = config.getString("canary");
                String sessionId = config.getString("sessionId");

                String referer = getRequest.getURI().toString();

                RestRequest getCredentialRequest = new RestRequest("https://login.microsoftonline.com/common/GetCredentialType");
                getCredentialRequest.setHeader("Accept", "application/json");
                getCredentialRequest.setHeader("canary", apiCanary);
                getCredentialRequest.setHeader("client-request-id", clientRequestId);
                getCredentialRequest.setHeader("hpgact", hpgact);
                getCredentialRequest.setHeader("hpgid", hpgid);
                getCredentialRequest.setHeader("hpgrequestid", sessionId);
                getCredentialRequest.setHeader("Referer", referer);

                final JSONObject jsonObject = new JSONObject();
                jsonObject.put("username", username);
                jsonObject.put("isOtherIdpSupported", true);
                jsonObject.put("checkPhones", false);
                jsonObject.put("isRemoteNGCSupported", false);
                jsonObject.put("isCookieBannerShown", false);
                jsonObject.put("isFidoSupported", false);
                jsonObject.put("flowToken", flowToken);
                jsonObject.put("originalRequest", context);

                getCredentialRequest.setJsonBody(jsonObject);

                httpClientAdapter.execute(getCredentialRequest);
                JSONObject credentialType = getCredentialRequest.getJsonResponse();
                System.out.println("CredentialType=" + credentialType);

                JSONObject credentials = credentialType.getJSONObject("Credentials");
                String federationRedirectUrl = credentials.optString("FederationRedirectUrl");
                System.out.println("federationRedirectUrl=" + federationRedirectUrl);

                if (federationRedirectUrl == null || federationRedirectUrl.isEmpty()) {
                    PostRequest logonMethod = new PostRequest(URI.create("https://login.microsoftonline.com/common/login"));
                    logonMethod.setHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                    logonMethod.setHeader("Content-Type", "application/x-www-form-urlencoded");

                    logonMethod.setHeader("Referer", referer);

                    logonMethod.setParameter("canary", canary);
                    logonMethod.setParameter("ctx", context);
                    logonMethod.setParameter("flowToken", flowToken);
                    logonMethod.setParameter("hpgrequestid", sessionId);
                    logonMethod.setParameter("login", username);
                    logonMethod.setParameter("loginfmt", username);
                    logonMethod.setParameter("passwd", password);

                    httpClientAdapter.execute(logonMethod);
                    URI location = HttpClientAdapter.getRedirectLocation(logonMethod.getResponse());
                    System.out.println(location);
                    if (location == null) {
                        System.out.println(getRequest.getResponsePart("Config=([^\n]+);"));
                    }
                    assertNotNull(location);

                    System.out.println(location.getQuery());

                    List<NameValuePair> responseParams = URLEncodedUtils.parse(location, Consts.UTF_8);
                    assertNotNull(responseParams.get(0));
                    assertEquals("code", responseParams.get(0).getName());

                }
            }
        }
    }


}

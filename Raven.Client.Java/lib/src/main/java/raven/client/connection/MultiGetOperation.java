package raven.client.connection;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.http.HttpStatus;
import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.map.JsonMappingException;

import raven.abstractions.closure.Action2;
import raven.abstractions.connection.profiling.RequestResultArgs;
import raven.abstractions.data.GetRequest;
import raven.abstractions.data.GetResponse;
import raven.abstractions.extensions.JsonExtensions;
import raven.client.connection.implementation.HttpJsonRequest;
import raven.client.connection.implementation.HttpJsonRequestFactory;
import raven.client.connection.profiling.IHoldProfilingInformation;
import raven.client.connection.profiling.RequestStatus;
import raven.client.document.DocumentConvention;

public class MultiGetOperation {
  private IHoldProfilingInformation holdProfilingInformation;
  private DocumentConvention convention;
  private String url;
  private GetRequest[] requests;
  private String requestUri;
  private boolean allRequestsCanBeServedFromAggressiveCache;
  private CachedRequest[] cachedData;

  public String getRequestUri() {
    return requestUri;
  }

  public MultiGetOperation(IHoldProfilingInformation holdProfilingInformation, DocumentConvention convention, String url, GetRequest[] requests) {
    this.holdProfilingInformation = holdProfilingInformation;
    this.convention = convention;
    this.url = url;
    this.requests = requests;

    requestUri = url + "/multi_get";
    if (convention.isUseParallelMultiGet())
    {
      requestUri += "?parallel=yes";
    }
  }

  private static class SetHeaders implements Action2<String, String> {
    private GetRequest getRequest;

    public SetHeaders(GetRequest getRequest) {
      this.getRequest = getRequest;
    }

    @Override
    public void apply(String headerName, String value) {
      getRequest.getHeaders().put(headerName, value);
    }

  }

  public GetRequest[] preparingForCachingRequest(HttpJsonRequestFactory jsonRequestFactory) {
    cachedData = new CachedRequest[requests.length];
    GetRequest[] requestsForServer = Arrays.copyOf(requests, requests.length);
    if (jsonRequestFactory.getDisableHttpCaching() == false && convention.shouldCacheRequest(requestUri)) {
      for (int i = 0; i < requests.length; i++) {
        GetRequest request = requests[i];
        CachedRequestOp cachingConfiguration = jsonRequestFactory.configureCaching(url + request.getUrlAndQuery(), new SetHeaders(request));
        cachedData[i] = cachingConfiguration.getCachedRequest();
        if (cachingConfiguration.isSkipServerCheck())
          requestsForServer[i] = null;
      }
    }
    boolean allNull = true;
    for(GetRequest request : requestsForServer) {
      allNull &= request == null;
    }
    allRequestsCanBeServedFromAggressiveCache = allNull;
    return requestsForServer;
  }

  public boolean canFullyCache(HttpJsonRequestFactory jsonRequestFactory, HttpJsonRequest httpJsonRequest, String postedData)
  {
    if (allRequestsCanBeServedFromAggressiveCache) { // can be fully served from aggressive cache
      RequestResultArgs args = new RequestResultArgs();

      args.setDurationMilliseconds(httpJsonRequest.calculateDuration());
      args.setMethod(httpJsonRequest.getMethod());
      args.setHttpResult(0);
      args.setStatus(RequestStatus.AGGRESSIVELY_CACHED);
      args.setResult("");
      args.setUrl(httpJsonRequest.getUrl());
      args.setPostedData(postedData);

      jsonRequestFactory.invokeLogRequest(holdProfilingInformation, args);
      return true;
    }
    return false;
  }

  public GetResponse[] handleCachingResponse(GetResponse[] responses, HttpJsonRequestFactory jsonRequestFactory) throws JsonGenerationException, JsonMappingException, IOException {
    boolean hasCachedRequests = false;
    RequestStatus[] requestStatuses = new RequestStatus[responses.length];
    for (int i = 0; i < responses.length; i++) {
      if (responses[i] == null || responses[i].getStatus() == HttpStatus.SC_NOT_MODIFIED) {
        hasCachedRequests = true;

        requestStatuses[i] = responses[i] == null ? RequestStatus.AGGRESSIVELY_CACHED : RequestStatus.CACHED;
        if (responses[i] == null) {
          responses[i] = new GetResponse();
          responses[i].setStatus(0);
        }

        for (String header: cachedData[i].getHeaders().keySet()) {
          responses[i].getHeaders().put(header, cachedData[i].getHeaders().get(header));
        }
        responses[i].setResult(cachedData[i].getData().cloneToken());
        jsonRequestFactory.incrementCachedRequests();
      } else {
        requestStatuses[i] = responses[i].isRequestHasErrors() ? RequestStatus.ERROR_ON_SERVER : RequestStatus.SEND_TO_SERVER;

        Map<String, String> nameValueCollection = new HashMap<String, String>();
        for (Map.Entry<String, String> header: responses[i].getHeaders().entrySet()) {
          nameValueCollection.put(header.getKey(), header.getValue());
        }
        jsonRequestFactory.cacheResponse(url + requests[i].getUrlAndQuery(), responses[i].getResult(), nameValueCollection);
      }
    }

    if (hasCachedRequests == false || convention.isDisableProfiling())
      return responses;

    List<RequestResultArgs> profilingRequests = holdProfilingInformation.getProfilingInformation().getRequests();
    RequestResultArgs lastRequest = profilingRequests.get(profilingRequests.size() - 1);
    for (int i = 0; i < requestStatuses.length; i++) {
      lastRequest.getAdditionalInformation().put("NestedRequestStatus-" + i, requestStatuses[i].toString());
    }
    lastRequest.setResult(JsonExtensions.getDefaultObjectMapper().writeValueAsString(responses));

    return responses;
  }

}

/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
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
 */
package com.dremio.provision.aws.util;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;

import software.amazon.awssdk.core.SdkSystemSetting;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkServiceException;
import software.amazon.awssdk.core.internal.util.UserAgentUtils;
import software.amazon.awssdk.regions.util.HttpResourcesUtils;
import software.amazon.awssdk.regions.util.ResourcesEndpointProvider;

/**
 * Utility class for retrieving Amazon EC2 instance metadata.<br>
 * Copied from <code>software.amazon.awssdk.regions.internal.util.EC2MetadataUtils</code><br>
 * <p>
 * More information about Amazon EC2 Metadata:
 *
 * @see <a
 * href="http://docs.aws.amazon.com/AWSEC2/latest/UserGuide/AESDG-chapter-instancedata.html">Amazon
 * EC2 User Guide: Instance Metadata</a>
 */
public final class EC2MetadataUtils {
  private static final Logger logger = LoggerFactory.getLogger(EC2MetadataUtils.class);

  /**
   * Default resource path for credentials in the Amazon EC2 Instance Metadata Service.
   */
  private static final String REGION = "region";
  private static final String INSTANCE_IDENTITY_DOCUMENT = "instance-identity/document";
  private static final String INSTANCE_IDENTITY_SIGNATURE = "instance-identity/signature";
  private static final String EC2_METADATA_ROOT = "/latest/meta-data";
  private static final String EC2_USERDATA_ROOT = "/latest/user-data/";
  private static final String EC2_DYNAMICDATA_ROOT = "/latest/dynamic/";

  private static final String EC2_METADATA_TOKEN_HEADER = "x-aws-ec2-metadata-token";

  private static final int DEFAULT_QUERY_RETRIES = 3;
  private static final int MINIMUM_RETRY_WAIT_TIME_MILLISECONDS = 250;
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

  private static final InstanceProviderTokenEndpointProvider TOKEN_ENDPOINT_PROVIDER =
    new InstanceProviderTokenEndpointProvider();

  private EC2MetadataUtils() {
  }

  static {
    MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    MAPPER.setPropertyNamingStrategy(PropertyNamingStrategy.UPPER_CAMEL_CASE);
  }

  /**
   * Get the AMI ID used to launch the instance.
   */
  public static String getAmiId() {
    return fetchData(EC2_METADATA_ROOT + "/ami-id");
  }

  /**
   * Get the index of this instance in the reservation.
   */
  public static String getAmiLaunchIndex() {
    return fetchData(EC2_METADATA_ROOT + "/ami-launch-index");
  }

  /**
   * Get the manifest path of the AMI with which the instance was launched.
   */
  public static String getAmiManifestPath() {
    return fetchData(EC2_METADATA_ROOT + "/ami-manifest-path");
  }

  /**
   * Get the list of AMI IDs of any instances that were rebundled to created
   * this AMI. Will only exist if the AMI manifest file contained an
   * ancestor-amis key.
   */
  public static List<String> getAncestorAmiIds() {
    return getItems(EC2_METADATA_ROOT + "/ancestor-ami-ids");
  }

  /**
   * Notifies the instance that it should reboot in preparation for bundling.
   * Valid values: none | shutdown | bundle-pending.
   */
  public static String getInstanceAction() {
    return fetchData(EC2_METADATA_ROOT + "/instance-action");
  }

  /**
   * Get the ID of this instance.
   */
  public static String getInstanceId() {
    return fetchData(EC2_METADATA_ROOT + "/instance-id");
  }

  /**
   * Get the type of the instance.
   */
  public static String getInstanceType() {
    return fetchData(EC2_METADATA_ROOT + "/instance-type");
  }

  /**
   * Get the local hostname of the instance. In cases where multiple network
   * interfaces are present, this refers to the eth0 device (the device for
   * which device-number is 0).
   */
  public static String getLocalHostName() {
    return fetchData(EC2_METADATA_ROOT + "/local-hostname");
  }

  /**
   * Get the MAC address of the instance. In cases where multiple network
   * interfaces are present, this refers to the eth0 device (the device for
   * which device-number is 0).
   */
  public static String getMacAddress() {
    return fetchData(EC2_METADATA_ROOT + "/mac");
  }

  /**
   * Get the private IP address of the instance. In cases where multiple
   * network interfaces are present, this refers to the eth0 device (the
   * device for which device-number is 0).
   */
  public static String getPrivateIpAddress() {
    return fetchData(EC2_METADATA_ROOT + "/local-ipv4");
  }

  /**
   * Get the Availability Zone in which the instance launched.
   */
  public static String getAvailabilityZone() {
    return fetchData(EC2_METADATA_ROOT + "/placement/availability-zone");
  }

  /**
   * Get the list of product codes associated with the instance, if any.
   */
  public static List<String> getProductCodes() {
    return getItems(EC2_METADATA_ROOT + "/product-codes");
  }

  /**
   * Get the public key. Only available if supplied at instance launch time.
   */
  public static String getPublicKey() {
    return fetchData(EC2_METADATA_ROOT + "/public-keys/0/openssh-key");
  }

  /**
   * Get the ID of the RAM disk specified at launch time, if applicable.
   */
  public static String getRamdiskId() {
    return fetchData(EC2_METADATA_ROOT + "/ramdisk-id");
  }

  /**
   * Get the ID of the reservation.
   */
  public static String getReservationId() {
    return fetchData(EC2_METADATA_ROOT + "/reservation-id");
  }

  /**
   * Get the list of names of the security groups applied to the instance.
   */
  public static List<String> getSecurityGroups() {
    return getItems(EC2_METADATA_ROOT + "/security-groups");
  }

  /**
   * Get information about the last time the instance profile was updated,
   * including the instance's LastUpdated date, InstanceProfileArn, and
   * InstanceProfileId.
   */
  public static IAMInfo getIamInstanceProfileInfo() {
    String json = getData(EC2_METADATA_ROOT + "/iam/info");
    if (null == json) {
      return null;
    }

    try {

      return MAPPER.readValue(json, IAMInfo.class);

    } catch (Exception e) {
      logger.warn("Unable to parse IAM Instance profile info (" + json
        + "): " + e.getMessage(), e);
      return null;
    }
  }

  /**
   * Get the signature of the instance.
   */
  public static String getInstanceSignature() {
    return fetchData(EC2_DYNAMICDATA_ROOT + INSTANCE_IDENTITY_SIGNATURE);
  }

  /**
   * Returns the current region of this running EC2 instance; or null if
   * it is unable to do so. The method avoids interpreting other parts of the
   * instance info JSON document to minimize potential failure.
   * <p>
   * The instance info is only guaranteed to be a JSON document per
   * http://docs
   * .aws.amazon.com/AWSEC2/latest/UserGuide/ec2-instance-metadata.html
   */
  public static String getEC2InstanceRegion() {
    return doGetEC2InstanceRegion(getData(
      EC2_DYNAMICDATA_ROOT + INSTANCE_IDENTITY_DOCUMENT));
  }

  static String doGetEC2InstanceRegion(final String json) {
    if (null != json) {
      try {
        JsonNode node = MAPPER.readTree(json.getBytes(StandardCharsets.UTF_8));
        JsonNode region = node.findValue(REGION);
        return region.asText();
      } catch (Exception e) {
        logger.warn("Unable to parse EC2 instance info (" + json
          + ") : " + e.getMessage(), e);
      }
    }
    return null;
  }

  /**
   * Returns the temporary security credentials (AccessKeyId, SecretAccessKey,
   * SessionToken, and Expiration) associated with the IAM roles on the
   * instance.
   */
  public static Map<String, IAMSecurityCredential> getIamSecurityCredentials() {
    Map<String, IAMSecurityCredential> credentialsInfoMap = new HashMap<>();

    List<String> credentials = getItems(EC2_METADATA_ROOT
      + "/iam/security-credentials");

    if (null != credentials) {
      for (String credential : credentials) {
        String json = getData(EC2_METADATA_ROOT
          + "/iam/security-credentials/" + credential);
        try {
          IAMSecurityCredential credentialInfo = MAPPER
            .readValue(json, IAMSecurityCredential.class);
          credentialsInfoMap.put(credential, credentialInfo);
        } catch (Exception e) {
          logger.warn("Unable to process the credential (" + credential
            + "). " + e.getMessage(), e);
        }
      }
    }
    return credentialsInfoMap;
  }

  /**
   * Get the virtual devices associated with the ami, root, ebs, and swap.
   */
  public static Map<String, String> getBlockDeviceMapping() {
    Map<String, String> blockDeviceMapping = new HashMap<>();

    List<String> devices = getItems(EC2_METADATA_ROOT
      + "/block-device-mapping");
    for (String device : devices) {
      blockDeviceMapping.put(device, getData(EC2_METADATA_ROOT
        + "/block-device-mapping/" + device));
    }
    return blockDeviceMapping;
  }

  /**
   * Get the list of network interfaces on the instance.
   */
  public static List<NetworkInterface> getNetworkInterfaces() {
    List<NetworkInterface> networkInterfaces = new LinkedList<>();

    List<String> macs = getItems(EC2_METADATA_ROOT
      + "/network/interfaces/macs/");
    for (String mac : macs) {
      String key = mac.trim();
      if (key.endsWith("/")) {
        key = key.substring(0, key.length() - 1);
      }
      networkInterfaces.add(new NetworkInterface(key));
    }
    return networkInterfaces;
  }

  /**
   * Get the metadata sent to the instance
   */
  public static String getUserData() {
    return getData(EC2_USERDATA_ROOT);
  }

  public static String getData(String path) {
    return getData(path, DEFAULT_QUERY_RETRIES);
  }

  public static String getData(String path, int tries) {
    List<String> items = getItems(path, tries, true);
    if (null != items && items.size() > 0) {
      return items.get(0);
    }
    return null;
  }

  public static List<String> getItems(String path) {
    return getItems(path, DEFAULT_QUERY_RETRIES, false);
  }

  public static List<String> getItems(String path, int tries) {
    return getItems(path, tries, false);
  }

  private static List<String> getItems(String path, int tries, boolean slurp) {
    if (tries == 0) {
      throw SdkClientException.builder().message("Unable to contact EC2 metadata service.").build();
    }

    if (SdkSystemSetting.AWS_EC2_METADATA_DISABLED.getBooleanValueOrThrow()) {
      throw SdkClientException.builder().message("EC2 metadata usage is disabled.").build();
    }

    List<String> items;

    String token = getToken();

    try {
      String hostAddress = SdkSystemSetting.AWS_EC2_METADATA_SERVICE_ENDPOINT.getStringValueOrThrow();
      String response = doReadResource(new URI(hostAddress + path), token);
      if (slurp) {
        items = Collections.singletonList(response);
      } else {
        items = Arrays.asList(response.split("\n"));
      }
      return items;
    } catch (SdkClientException ace) {
      logger.warn("Unable to retrieve the requested metadata.");
      return null;
    } catch (IOException | URISyntaxException | RuntimeException e) {
      // Retry on any other exceptions
      int pause = (int) (Math.pow(2, DEFAULT_QUERY_RETRIES - tries) * MINIMUM_RETRY_WAIT_TIME_MILLISECONDS);
      try {
        Thread.sleep(Math.max(pause, MINIMUM_RETRY_WAIT_TIME_MILLISECONDS));
      } catch (InterruptedException e1) {
        Thread.currentThread().interrupt();
      }
      return getItems(path, tries - 1, slurp);
    }
  }

  private static String doReadResource(URI resource, String token) throws IOException {
    return HttpResourcesUtils.instance().readResource(new DefaultEndpointProvider(resource, token), "GET");
  }

  public static String getToken() {
    try {
      return HttpResourcesUtils.instance().readResource(TOKEN_ENDPOINT_PROVIDER, "PUT");
    } catch (Exception e) {

      boolean is400ServiceException = e instanceof SdkServiceException
        && ((SdkServiceException) e).statusCode() == 400;

      // metadata resolution must not continue to the token-less flow for a 400
      if (is400ServiceException) {
        throw SdkClientException.builder()
          .message("Unable to fetch metadata token")
          .cause(e)
          .build();
      }

      return null;
    }
  }

  private static String fetchData(String path) {
    if (SdkSystemSetting.AWS_EC2_METADATA_DISABLED.getBooleanValueOrThrow()) {
      throw SdkClientException.builder().message("EC2 metadata usage is disabled.").build();
    }

    try {
      if (!CACHE.containsKey(path)) {
        CACHE.put(path, getData(path));
      }
      return CACHE.get(path);
    } catch (SdkClientException e) {
      throw e;
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * Information about the last time the instance profile was updated,
   * including the instance's LastUpdated date, InstanceProfileArn, and
   * InstanceProfileId.
   */
  @SuppressWarnings("checkstyle:VisibilityModifier")
  public static class IAMInfo {
    public String code;
    public String message;
    public String lastUpdated;
    public String instanceProfileArn;
    public String instanceProfileId;
  }

  /**
   * The temporary security credentials (AccessKeyId, SecretAccessKey,
   * SessionToken, and Expiration) associated with the IAM role.
   */
  @SuppressWarnings("checkstyle:VisibilityModifier")
  public static class IAMSecurityCredential {
    public String code;
    public String message;
    public String lastUpdated;
    public String type;
    public String accessKeyId;
    public String secretAccessKey;
    public String token;
    public String expiration;

    /**
     * @see #accessKeyId
     * @deprecated because it is spelled incorrectly
     */
    @Deprecated
    public String secretAcessKey;
  }

  public static IAMInfo getIAMInstanceProfileInfo() {
    String json = getData("/latest/meta-data/iam/info");
    if (null == json) {
      return null;
    } else {
      try {
        return MAPPER.readValue(json, IAMInfo.class);
      } catch (Exception e) {
        logger.warn("Unable to parse IAM Instance profile info (" + json + ").", e);
        return null;
      }
    }
  }

  public static Map<String, IAMSecurityCredential> getIAMSecurityCredentials() {
    Map<String, IAMSecurityCredential> credentialsInfoMap = new HashMap<>();
    List<String> credentials = getItems("/latest/meta-data/iam/security-credentials");
    if (null != credentials) {
      for (String credential : credentials) {
        String json = getData("/latest/meta-data/iam/security-credentials/" + credential);

        try {
          IAMSecurityCredential credentialInfo = MAPPER.readValue(json, IAMSecurityCredential.class);
          credentialsInfoMap.put(credential, credentialInfo);
        } catch (Exception e) {
          logger.warn("Unable to process the credential (" + credential + ").", e);
        }
      }
    }

    return credentialsInfoMap;
  }

  /**
   * All of the metadata associated with a network interface on the instance.
   */
  public static class NetworkInterface {
    private final String path;
    private final String mac;
    private final Map<String, String> data = new HashMap<>();

    private List<String> availableKeys;

    public NetworkInterface(String macAddress) {
      mac = macAddress;
      path = "/network/interfaces/macs/" + mac + "/";
    }

    /**
     * The interface's Media Acess Control (mac) address
     */
    public String getMacAddress() {
      return mac;
    }

    /**
     * The ID of the owner of the network interface.<br>
     * In multiple-interface environments, an interface can be attached by a
     * third party, such as Elastic Load Balancing. Traffic on an interface
     * is always billed to the interface owner.
     */
    public String getOwnerId() {
      return getData("owner-id");
    }

    /**
     * The interface's profile.
     */
    public String getProfile() {
      return getData("profile");
    }

    /**
     * The interface's local hostname.
     */
    public String getHostname() {
      return getData("local-hostname");
    }

    /**
     * The private IP addresses associated with the interface.
     */
    public List<String> getLocalIPv4s() {
      return getItems("local-ipv4s");
    }

    /**
     * The interface's public hostname.
     */
    public String getPublicHostname() {
      return getData("public-hostname");
    }

    /**
     * The elastic IP addresses associated with the interface.<br>
     * There may be multiple IP addresses on an instance.
     */
    public List<String> getPublicIPv4s() {
      return getItems("public-ipv4s");
    }

    /**
     * Security groups to which the network interface belongs.
     */
    public List<String> getSecurityGroups() {
      return getItems("security-groups");
    }

    /**
     * IDs of the security groups to which the network interface belongs.
     * Returned only for Amazon EC2 instances launched into a VPC.
     */
    public List<String> getSecurityGroupIds() {
      return getItems("security-group-ids");
    }

    /**
     * The CIDR block of the Amazon EC2-VPC subnet in which the interface
     * resides.<br>
     * Returned only for Amazon EC2 instances launched into a VPC.
     */
    public String getSubnetIPv4CidrBlock() {
      return getData("subnet-ipv4-cidr-block");
    }

    /**
     * ID of the subnet in which the interface resides.<br>
     * Returned only for Amazon EC2 instances launched into a VPC.
     */
    public String getSubnetId() {
      return getData("subnet-id");
    }

    /**
     * The CIDR block of the Amazon EC2-VPC in which the interface
     * resides.<br>
     * Returned only for Amazon EC2 instances launched into a VPC.
     */
    public String getVpcIPv4CidrBlock() {
      return getData("vpc-ipv4-cidr-block");
    }

    /**
     * ID of the Amazon EC2-VPC in which the interface resides.<br>
     * Returned only for Amazon EC2 instances launched into a VPC.
     */
    public String getVpcId() {
      return getData("vpc-id");
    }

    /**
     * Get the private IPv4 address(es) that are associated with the
     * public-ip address and assigned to that interface.
     *
     * @param publicIp The public IP address
     * @return Private IPv4 address(es) associated with the public IP
     * address.
     */
    public List<String> getIPv4Association(String publicIp) {
      return getItems(EC2_METADATA_ROOT + path + "ipv4-associations/" + publicIp);
    }

    private String getData(String key) {
      if (data.containsKey(key)) {
        return data.get(key);
      }

      // Since the keys are variable, cache a list of which ones are
      // available to prevent unnecessary trips to the service.
      if (null == availableKeys) {
        availableKeys = EC2MetadataUtils.getItems(EC2_METADATA_ROOT
          + path);
      }

      if (availableKeys.contains(key)) {
        data.put(key, EC2MetadataUtils.getData(EC2_METADATA_ROOT + path
          + key));
        return data.get(key);
      } else {
        return null;
      }
    }

    private List<String> getItems(String key) {
      if (null == availableKeys) {
        availableKeys = EC2MetadataUtils.getItems(EC2_METADATA_ROOT
          + path);
      }

      if (availableKeys.contains(key)) {
        return EC2MetadataUtils
          .getItems(EC2_METADATA_ROOT + path + key);
      } else {
        return Collections.emptyList();
      }
    }
  }

  private static final class DefaultEndpointProvider implements ResourcesEndpointProvider {
    private final URI endpoint;
    private final String metadataToken;

    private DefaultEndpointProvider(URI endpoint, String metadataToken) {
      this.endpoint = endpoint;
      this.metadataToken = metadataToken;
    }

    @Override
    public URI endpoint() {
      return endpoint;
    }

    @Override
    public Map<String, String> headers() {
      Map<String, String> requestHeaders = new HashMap<>();
      requestHeaders.put("User-Agent", UserAgentUtils.getUserAgent());
      requestHeaders.put("Accept", "*/*");
      requestHeaders.put("Connection", "keep-alive");

      if (metadataToken != null) {
        requestHeaders.put(EC2_METADATA_TOKEN_HEADER, metadataToken);
      }

      return requestHeaders;
    }
  }

  /**
   * Copied from <code>software.amazon.awssdk.regions.internal.util.InstanceProviderTokenEndpointProvider</code>
   */
  private static final class InstanceProviderTokenEndpointProvider implements ResourcesEndpointProvider {
    private static final String TOKEN_RESOURCE_PATH = "/latest/api/token";
    private static final String EC2_METADATA_TOKEN_TTL_HEADER = "x-aws-ec2-metadata-token-ttl-seconds";
    private static final String DEFAULT_TOKEN_TTL = "21600";

    @Override
    public URI endpoint() {
      String host = SdkSystemSetting.AWS_EC2_METADATA_SERVICE_ENDPOINT.getStringValueOrThrow();
      if (host.endsWith("/")) {
        host = host.substring(0, host.length() - 1);
      }
      return URI.create(host + TOKEN_RESOURCE_PATH);
    }

    @Override
    public Map<String, String> headers() {
      Map<String, String> requestHeaders = new HashMap<>();
      requestHeaders.put("User-Agent", UserAgentUtils.getUserAgent());
      requestHeaders.put("Accept", "*/*");
      requestHeaders.put("Connection", "keep-alive");
      requestHeaders.put(EC2_METADATA_TOKEN_TTL_HEADER, DEFAULT_TOKEN_TTL);

      return requestHeaders;
    }
  }
}

/*
 * Copyright 2014 CyberVision, Inc.
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

package org.kaaproject.kaa.server.operations.service.profile;

import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.List;

import org.kaaproject.kaa.common.avro.GenericAvroConverter;
import org.kaaproject.kaa.common.dto.EndpointGroupStateDto;
import org.kaaproject.kaa.common.dto.EndpointProfileDto;
import org.kaaproject.kaa.common.dto.EventClassFamilyVersionStateDto;
import org.kaaproject.kaa.common.dto.ProfileSchemaDto;
import org.kaaproject.kaa.common.endpoint.security.KeyUtil;
import org.kaaproject.kaa.common.dto.admin.SdkPropertiesDto;
import org.kaaproject.kaa.common.dto.event.ApplicationEventFamilyMapDto;
import org.kaaproject.kaa.common.hash.EndpointObjectHash;
import org.kaaproject.kaa.server.common.dao.ApplicationService;
import org.kaaproject.kaa.server.common.dao.EndpointService;
import org.kaaproject.kaa.server.operations.pojo.RegisterProfileRequest;
import org.kaaproject.kaa.server.operations.pojo.UpdateProfileRequest;
import org.kaaproject.kaa.server.operations.service.cache.AppSeqNumber;
import org.kaaproject.kaa.server.operations.service.cache.AppVersionKey;
import org.kaaproject.kaa.server.operations.service.cache.CacheService;
import org.kaaproject.kaa.server.operations.service.cache.EventClassFamilyIdKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The Class DefaultProfileService is a default implementation of
 * {@link ProfileService ProfileService}.
 *
 * @author ashvayka
 */
public class DefaultProfileService implements ProfileService {

    /** The LOG constant. */
    private static final Logger LOG = LoggerFactory.getLogger(DefaultProfileService.class);

    /** The application service. */
    @Autowired
    private ApplicationService applicationService;

    /** The endpoint service. */
    @Autowired
    private EndpointService endpointService;

    /** The profile service. */
    @Autowired
    private org.kaaproject.kaa.server.common.dao.ProfileService profileService;

    /** The endpoint service. */
    @Autowired
    private CacheService cacheService;

    /*
     * (non-Javadoc)
     *
     * @see org.kaaproject.kaa.server.operations.service.profile.ProfileService#
     * getProfile (org.kaaproject.kaa.common.hash.EndpointObjectHash)
     */
    @Override
    public EndpointProfileDto getProfile(EndpointObjectHash endpointKey) {
        return endpointService.findEndpointProfileByKeyHash(endpointKey.getData());
    }

    @Override
    public EndpointProfileDto updateProfile(EndpointProfileDto profile) {
        LOG.debug("Updating profile {} ", profile);
        return endpointService.saveEndpointProfile(profile);
    }

    /*
     * (non-Javadoc)
     *
     * @see org.kaaproject.kaa.server.operations.service.profile.ProfileService#
     * registerProfile
     * (org.kaaproject.kaa.server.operations.pojo.RegisterProfileRequest)
     */
    @Override
    public EndpointProfileDto registerProfile(RegisterProfileRequest request) {
        LOG.debug("Registering Profile for {}", request.getEndpointKey());
        LOG.trace("Lookup application by token: {}", request.getAppToken());

        AppSeqNumber appSeqNumber = cacheService.getAppSeqNumber(request.getAppToken());
        LOG.trace("Application by token: {} found: {}", request.getAppToken(), appSeqNumber);

        SdkPropertiesDto sdkProperties = cacheService.getSdkPropertiesBySdkToken(request.getSdkToken());
        LOG.trace("Sdk properties by sdk token: {} found: {}", request.getSdkToken(), sdkProperties);

        String profileJson = decodeProfile(request.getProfile(), appSeqNumber.getAppToken(), sdkProperties.getProfileSchemaVersion());

        EndpointObjectHash keyHash = EndpointObjectHash.fromSHA1(request.getEndpointKey());

        EndpointProfileDto dto = endpointService.findEndpointProfileByKeyHash(keyHash.getData());
        if (dto == null) {
            dto = new EndpointProfileDto();
            dto.setSdkToken(sdkProperties.getToken());
            dto.setApplicationId(appSeqNumber.getAppId());
            dto.setEndpointKey(request.getEndpointKey());
            dto.setEndpointKeyHash(keyHash.getData());
            dto.setProfile(profileJson);
            dto.setProfileHash(EndpointObjectHash.fromSHA1(request.getProfile()).getData());

            populateVersionStates(appSeqNumber.getTenantId(), dto, sdkProperties);

            if (request.getAccessToken() != null) {
                dto.setAccessToken(request.getAccessToken());
            }

            dto.setCfSequenceNumber(0);
            dto.setNfSequenceNumber(0);
            dto.setChangedFlag(Boolean.FALSE);

            try {
                cacheService.putEndpointKey(keyHash, KeyUtil.getPublic(dto.getEndpointKey()));
            } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
                LOG.error("Can't generate public key for endpoint key: {}. Reason: {}", dto.getEndpointKey(), e);
                throw new RuntimeException(e);
            }

            return endpointService.saveEndpointProfile(dto);
        } else {
            return updateProfile(new UpdateProfileRequest(request.getAppToken(), keyHash, request.getAccessToken(), request.getProfile(),
                    request.getSdkToken()));
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see org.kaaproject.kaa.server.operations.service.profile.ProfileService#
     * updateProfile
     * (org.kaaproject.kaa.server.operations.pojo.UpdateProfileRequest)
     */
    @Override
    public EndpointProfileDto updateProfile(UpdateProfileRequest request) {
        LOG.debug("Updating Profile for {}", request.getEndpointKeyHash());

        EndpointProfileDto dto = endpointService.findEndpointProfileByKeyHash(request.getEndpointKeyHash().getData());

        AppSeqNumber appSeqNumber = cacheService.getAppSeqNumber(request.getApplicationToken());

        SdkPropertiesDto sdkProperties = cacheService.getSdkPropertiesBySdkToken(request.getSdkToken());
        String profileJson = decodeProfile(request.getProfile(), appSeqNumber.getAppToken(), sdkProperties.getProfileSchemaVersion());

        if (request.getAccessToken() != null) {
            dto.setAccessToken(request.getAccessToken());
        }
        dto.setProfile(profileJson);
        dto.setProfileHash(EndpointObjectHash.fromSHA1(request.getProfile()).getData());

        populateVersionStates(appSeqNumber.getTenantId(), dto, sdkProperties);

        List<EndpointGroupStateDto> egsList = new ArrayList<>();
        dto.setCfGroupStates(egsList);
        dto.setCfSequenceNumber(0);
        dto.setNfGroupStates(egsList);
        dto.setNfSequenceNumber(0);
        return endpointService.saveEndpointProfile(dto);
    }

    protected void populateVersionStates(String tenantId, EndpointProfileDto dto, SdkPropertiesDto sdkProperties) {
        dto.setProfileVersion(sdkProperties.getProfileSchemaVersion());
        dto.setConfigurationVersion(sdkProperties.getConfigurationSchemaVersion());
        dto.setUserNfVersion(sdkProperties.getNotificationSchemaVersion());
        dto.setLogSchemaVersion(sdkProperties.getLogSchemaVersion());
        if (sdkProperties.getAefMapIds() != null) {
            List<ApplicationEventFamilyMapDto> aefMaps = cacheService.getApplicationEventFamilyMapsByIds(sdkProperties.getAefMapIds());
            List<EventClassFamilyVersionStateDto> ecfVersionStates = new ArrayList<>(aefMaps.size());
            for (ApplicationEventFamilyMapDto aefMap : aefMaps) {
                EventClassFamilyVersionStateDto ecfVersionDto = new EventClassFamilyVersionStateDto();
                String ecfId = cacheService.getEventClassFamilyIdByName(new EventClassFamilyIdKey(tenantId, aefMap.getEcfName()));
                if (ecfId != null) {
                    ecfVersionDto.setEcfId(ecfId);
                    ecfVersionDto.setVersion(aefMap.getVersion());
                    ecfVersionStates.add(ecfVersionDto);
                } else {
                    LOG.warn("Failed to add ecf version state for ecf name {} and version {}", aefMap.getEcfName(),
                            aefMap.getVersion());
                }
            }
            dto.setEcfVersionStates(ecfVersionStates);
        }
    }

    /**
     * Decode profile.
     *
     * @param profileRaw
     *            the profile raw
     * @param appId
     *            the app id
     * @param schemaVersion
     *            the schema version
     * @return the string
     */
    private String decodeProfile(byte[] profileRaw, String appToken, int schemaVersion) {
        LOG.trace("Lookup profileSchema by appToken: {} and version: {}", appToken, schemaVersion);

        ProfileSchemaDto profileSchemaDto = cacheService.getProfileSchemaByAppAndVersion(new AppVersionKey(appToken, schemaVersion));
        String profileSchema = profileSchemaDto.getSchema();

        LOG.trace("ProfileSchema by appToken: {} and version: {} found: {}", appToken, schemaVersion, profileSchema);

        String profileJson = GenericAvroConverter.toJson(profileRaw, profileSchema);
        LOG.trace("Profile json : {} ", profileJson);

        return profileJson;
    }
}

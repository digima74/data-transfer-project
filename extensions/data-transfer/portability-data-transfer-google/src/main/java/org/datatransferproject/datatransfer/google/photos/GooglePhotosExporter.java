/*
 * Copyright 2018 The Data Transfer Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.datatransferproject.datatransfer.google.photos;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.json.JsonFactory;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.datatransferproject.datatransfer.google.common.GoogleCredentialFactory;
import org.datatransferproject.datatransfer.google.photos.model.AlbumListResponse;
import org.datatransferproject.datatransfer.google.photos.model.GoogleAlbum;
import org.datatransferproject.datatransfer.google.photos.model.GoogleMediaItem;
import org.datatransferproject.datatransfer.google.photos.model.MediaItemSearchResponse;
import org.datatransferproject.spi.cloud.storage.JobStore;
import org.datatransferproject.spi.transfer.provider.ExportResult;
import org.datatransferproject.spi.transfer.provider.ExportResult.ResultType;
import org.datatransferproject.spi.transfer.provider.Exporter;
import org.datatransferproject.spi.transfer.types.ContinuationData;
import org.datatransferproject.spi.transfer.types.ExportInformation;
import org.datatransferproject.spi.transfer.types.IdOnlyContainerResource;
import org.datatransferproject.spi.transfer.types.PaginationData;
import org.datatransferproject.spi.transfer.types.StringPaginationToken;
import org.datatransferproject.spi.transfer.types.TempPhotosData;
import org.datatransferproject.types.transfer.auth.TokensAndUrlAuthData;
import org.datatransferproject.types.transfer.models.photos.PhotoAlbum;
import org.datatransferproject.types.transfer.models.photos.PhotoModel;
import org.datatransferproject.types.transfer.models.photos.PhotosContainerResource;

// Not ready for prime-time!
// TODO: fix duplication problems introduced by exporting all photos in 'root' directory first

public class GooglePhotosExporter
    implements Exporter<TokensAndUrlAuthData, PhotosContainerResource> {

  static final String ALBUM_TOKEN_PREFIX = "album:";
  static final String PHOTO_TOKEN_PREFIX = "media:";

  private final GoogleCredentialFactory credentialFactory;
  private final JobStore jobStore;
  private final JsonFactory jsonFactory;
  private volatile GooglePhotosInterface photosInterface;

  public GooglePhotosExporter(GoogleCredentialFactory credentialFactory, JobStore jobStore,
      JsonFactory jsonFactory) {
    this.credentialFactory = credentialFactory;
    this.jobStore = jobStore;
    this.jsonFactory = jsonFactory;
  }

  @VisibleForTesting
  GooglePhotosExporter(GoogleCredentialFactory credentialFactory, JobStore jobStore,
      JsonFactory jsonFactory, GooglePhotosInterface photosInterface) {
    this.credentialFactory = credentialFactory;
    this.jobStore = jobStore;
    this.jsonFactory = jsonFactory;
    this.photosInterface = photosInterface;
  }

  @Override
  public ExportResult<PhotosContainerResource> export(UUID jobId, TokensAndUrlAuthData authData,
      Optional<ExportInformation> exportInformation) throws IOException {
    if (!exportInformation.isPresent()) {
      // Make list of photos contained in albums so they are not exported twice later on
      populateContainedPhotosList(jobId, authData);
      return exportAlbums(authData, Optional.empty());
    }
    /* Use the export information to determine whether this export call should export albums or
    photos.
    Albums are exported if and only if the export information doesn't hold an album
    already, and the pagination token begins with the album prefix.  There must be a pagination
    token for album export since this is isn't the first export operation performed (if it was,
    there wouldn't be any export information at all).
    Otherwise, photos are exported.  If photos are exported, there may or may not be pagination
    information, and there may or may not be album information.
    If there is no container resource, that means that we're exporting albumless photos and a
    pagination token must be present.  The beginning step of exporting albumless photos is
    indicated by a pagination token containing only PHOTO_TOKEN_PREFIX with no token attached, in
    order to differentiate this case for the first step of export (no export information at all).
     */
    StringPaginationToken paginationToken =
        (StringPaginationToken) exportInformation.get().getPaginationData();
    IdOnlyContainerResource idOnlyContainerResource =
        (IdOnlyContainerResource) exportInformation.get().getContainerResource();

    boolean containerResourcePresent = idOnlyContainerResource != null;
    boolean paginationDataPresent = paginationToken != null;

    if (!containerResourcePresent
        && paginationDataPresent && paginationToken.getToken().startsWith(ALBUM_TOKEN_PREFIX)) {
      return exportAlbums(authData, Optional.of(paginationToken));
    } else {
      return exportPhotos(authData,
          Optional.ofNullable(idOnlyContainerResource),
          Optional.ofNullable(paginationToken), jobId);
    }
  }

  /**
   * Note: not all accounts have albums to return.  In that case, we just return an empty list of
   * albums instead of trying to iterate through a null list.
   */
  @VisibleForTesting
  ExportResult<PhotosContainerResource> exportAlbums(TokensAndUrlAuthData authData,
      Optional<PaginationData> paginationData) throws IOException {
    Optional<String> paginationToken = Optional.empty();
    if (paginationData.isPresent()) {
      String token = ((StringPaginationToken) paginationData.get()).getToken();
      Preconditions.checkArgument(
          token.startsWith(ALBUM_TOKEN_PREFIX), "Invalid pagination token " + token);
      paginationToken = Optional.of(token.substring(ALBUM_TOKEN_PREFIX.length()));
    }

    AlbumListResponse albumListResponse;

    albumListResponse = getOrCreatePhotosInterface(authData).listAlbums(paginationToken);

    PaginationData nextPageData;
    String token = albumListResponse.getNextPageToken();
    List<PhotoAlbum> albums = new ArrayList<>();
    GoogleAlbum[] googleAlbums = albumListResponse.getAlbums();

    if (Strings.isNullOrEmpty(token)) {
      nextPageData = new StringPaginationToken(PHOTO_TOKEN_PREFIX);
    } else {
      nextPageData = new StringPaginationToken(ALBUM_TOKEN_PREFIX + token);
    }
    ContinuationData continuationData = new ContinuationData(nextPageData);

    for (GoogleAlbum googleAlbum : googleAlbums) {
      // Add album info to list so album can be recreated later
      albums.add(
          new PhotoAlbum(
              googleAlbum.getId(),
              googleAlbum.getTitle(),
              null));

      // Add album id to continuation data
      continuationData.addContainerResource(new IdOnlyContainerResource(googleAlbum.getId()));
    }

    ResultType resultType = ResultType.CONTINUE;

    PhotosContainerResource containerResource = new PhotosContainerResource(albums, null);
    ExportResult<PhotosContainerResource> exportResult = new ExportResult<>(resultType,
        containerResource, continuationData);
    return exportResult;
  }

  @VisibleForTesting
  ExportResult<PhotosContainerResource> exportPhotos(TokensAndUrlAuthData authData,
      Optional<IdOnlyContainerResource> albumData,
      Optional<PaginationData> paginationData, UUID jobId) throws IOException {
    Optional<String> albumId = Optional.empty();
    if (albumData.isPresent()) {
      albumId = Optional.of(albumData.get().getId());
    }
    Optional<String> paginationToken = getPhotosPaginationToken(paginationData);

    MediaItemSearchResponse mediaItemSearchResponse = getOrCreatePhotosInterface(authData)
        .listMediaItems(albumId, paginationToken);

    PaginationData nextPageData = null;
    if (!Strings.isNullOrEmpty(mediaItemSearchResponse.getNextPageToken())) {
      nextPageData = new StringPaginationToken(
          PHOTO_TOKEN_PREFIX + mediaItemSearchResponse.getNextPageToken());
    }
    ContinuationData continuationData = new ContinuationData(nextPageData);

    PhotosContainerResource containerResource = null;
    GoogleMediaItem[] mediaItems = mediaItemSearchResponse.getMediaItems();
    if (mediaItems != null && mediaItems.length > 0) {
      List<PhotoModel> photos = convertPhotosList(albumId, mediaItems, jobId);
      containerResource = new PhotosContainerResource(null, photos);
    }

    ResultType resultType = ResultType.CONTINUE;
    if (nextPageData == null) {
      resultType = ResultType.END;
    }

    return new ExportResult<>(resultType, containerResource, continuationData);
  }


  /**
   * Method for storing a list of all photos that are already contained in albums
   */
  @VisibleForTesting
  void populateContainedPhotosList(UUID jobId, TokensAndUrlAuthData authData)
      throws IOException {
    // This method is only called once at the beginning of the transfer, so we can start by
    // initializing a new TempPhotosData to be store in the job store.
    TempPhotosData tempPhotosData = new TempPhotosData(jobId);

    String albumToken = null;
    AlbumListResponse albumListResponse;
    MediaItemSearchResponse containedMediaSearchResponse;
    do {
      albumListResponse = getOrCreatePhotosInterface(authData)
          .listAlbums(Optional.ofNullable(albumToken));
      for (GoogleAlbum album : albumListResponse.getAlbums()) {
        String albumId = album.getId();
        String photoToken = null;
        do {
          containedMediaSearchResponse = getOrCreatePhotosInterface(authData)
              .listMediaItems(Optional.of(albumId), Optional.ofNullable(photoToken));
          for (GoogleMediaItem mediaItem : containedMediaSearchResponse.getMediaItems()) {
            tempPhotosData.addContainedPhotoId(mediaItem.getId());
          }
          photoToken = containedMediaSearchResponse.getNextPageToken();
        } while (photoToken != null);
      }
      albumToken = albumListResponse.getNextPageToken();
    } while (albumToken != null);

    jobStore.create(jobId, createCacheKey(), tempPhotosData);
  }

  private Optional<String> getPhotosPaginationToken(Optional<PaginationData> paginationData) {
    Optional<String> paginationToken = Optional.empty();
    if (paginationData.isPresent()) {
      String token = ((StringPaginationToken) paginationData.get()).getToken();
      Preconditions
          .checkArgument(token.startsWith(PHOTO_TOKEN_PREFIX), "Invalid pagination token " + token);
      if (token.length() > PHOTO_TOKEN_PREFIX.length()) {
        paginationToken = Optional.of(token.substring(PHOTO_TOKEN_PREFIX.length()));
      }
    }
    return paginationToken;
  }

  private List<PhotoModel> convertPhotosList(Optional<String> albumId,
      GoogleMediaItem[] mediaItems, UUID jobId) throws IOException {
    List<PhotoModel> photos = new ArrayList<>(mediaItems.length);
    for (GoogleMediaItem mediaItem : mediaItems) {
      if (mediaItem.getMediaMetadata().getPhoto() != null) {
        // TODO: address videos
        if (albumId.isPresent() ||
            !jobStore.findData(jobId, createCacheKey(), TempPhotosData.class)
                .isContainedPhotoId(mediaItem.getId())) {
          photos.add(convertToPhotoModel(albumId, mediaItem));
        }
      }
    }
    return photos;
  }

  private PhotoModel convertToPhotoModel(Optional<String> albumId, GoogleMediaItem mediaItem) {
    Preconditions.checkArgument(mediaItem.getMediaMetadata().getPhoto() != null);

    return new PhotoModel(
        "", // TODO: no title?
        mediaItem.getBaseUrl() + "=d",
        mediaItem.getDescription(),
        mediaItem.getMimeType(),
        mediaItem.getId(),
        albumId.isPresent() ? albumId.get() : null,
        false);
  }

  private synchronized GooglePhotosInterface getOrCreatePhotosInterface(
      TokensAndUrlAuthData authData) {
    return photosInterface == null ? makePhotosInterface(authData) : photosInterface;
  }

  private synchronized GooglePhotosInterface makePhotosInterface(TokensAndUrlAuthData authData) {
    Credential credential = credentialFactory.createCredential(authData);
    return new GooglePhotosInterface(credential, jsonFactory);
  }

  private static String createCacheKey() {
    return "tempPhotosData";
  }
}

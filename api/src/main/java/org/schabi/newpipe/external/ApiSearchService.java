package org.schabi.newpipe.external;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class ApiSearchService extends Service {

    private static final String TAG = "ApiSearchService";

    public static final String ACTION_SEARCH = "org.schabi.newpipe.action.SEARCH";

    private static final String NOTIFICATION_CHANNEL_ID = "newpipe_api_search";
    private static final int NOTIFICATION_ID = 1001;

    private Disposable disposable;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(@Nullable final Intent intent, final int flags, final int startId) {
        if (intent == null || !ACTION_SEARCH.equals(intent.getAction())) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        final String query = intent.getStringExtra(SearchReceiver.EXTRA_QUERY);
        final int serviceId = intent.getIntExtra(SearchReceiver.EXTRA_SERVICE_ID, 0);
        final String resultReceiver = intent.getStringExtra(SearchReceiver.EXTRA_RESULT_RECEIVER);
        final boolean includeStreamUrls = intent.getBooleanExtra(
                SearchReceiver.EXTRA_INCLUDE_STREAM_URLS, false);

        if (query == null || query.trim().isEmpty()) {
            sendErrorBroadcast(resultReceiver, "Query parameter is missing or empty");
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification("Searching…"));
        performSearch(query.trim(), serviceId, resultReceiver, includeStreamUrls, startId);

        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(final Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }
        super.onDestroy();
    }

    private void performSearch(final String query,
                               final int serviceId,
                               @Nullable final String resultReceiver,
                               final boolean includeStreamUrls,
                               final int startId) {
        Log.d(TAG, "Starting search query='" + query + "' serviceId=" + serviceId);

        final Single<List<SearchResult>> single = Single
                .fromCallable(() -> {
                    final StreamingService service = NewPipe.getService(serviceId);
                    return SearchInfo.getInfo(service,
                            service.getSearchQHFactory().fromQuery(
                                    query,
                                    Collections.emptyList(),
                                    ""));
                })
                .map(this::extractVideoUrls)
                .map(this::limitToThree)
                .flatMap(results -> {
                    if (!includeStreamUrls) {
                        return Single.just(results);
                    }

                    return Single.fromCallable(() -> enrichFirstWithStreams(serviceId, results));
                })
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io());

        disposable = single.subscribe(
                results -> {
                    Log.d(TAG, "Search success results=" + results.size());
                    sendSuccessBroadcast(resultReceiver, results);
                    stopForeground(true);
                    stopSelf(startId);
                },
                error -> {
                    final String message = error.getMessage() != null
                            ? error.getMessage()
                            : "Unknown error";
                    Log.e(TAG, "Search error: " + message, error);
                    sendErrorBroadcast(resultReceiver, message);
                    stopForeground(true);
                    stopSelf(startId);
                }
        );
    }

    private List<SearchResult> extractVideoUrls(final SearchInfo searchInfo) {
        final List<SearchResult> results = new ArrayList<>();

        final List<InfoItem> items = searchInfo.getRelatedItems();
        if (items == null) {
            return results;
        }

        for (final InfoItem item : items) {
            if (item instanceof StreamInfoItem) {
                final StreamInfoItem streamItem = (StreamInfoItem) item;
                final SearchResult result = new SearchResult();

                result.title = streamItem.getName();
                result.url = streamItem.getUrl();
                result.thumbnailUrl = chooseFirstImageUrl(streamItem.getThumbnails());
                result.uploaderName = streamItem.getUploaderName();
                result.duration = streamItem.getDuration();
                result.viewCount = streamItem.getViewCount();

                results.add(result);
            }
        }

        return results;
    }

    private String chooseFirstImageUrl(@Nullable final List<Image> images) {
        if (images == null || images.isEmpty()) {
            return "";
        }

        for (final Image image : images) {
            if (image != null && image.getUrl() != null && !image.getUrl().isEmpty()) {
                return image.getUrl();
            }
        }

        return "";
    }

    private void sendSuccessBroadcast(@Nullable final String resultReceiver,
                                      final List<SearchResult> results) {
        try {
            final JSONArray jsonArray = new JSONArray();
            for (final SearchResult result : results) {
                final JSONObject jsonObject = new JSONObject();
                jsonObject.put("title", result.title);
                jsonObject.put("url", result.url);
                jsonObject.put("thumbnailUrl", result.thumbnailUrl);
                jsonObject.put("uploaderName", result.uploaderName);
                jsonObject.put("duration", result.duration);
                jsonObject.put("viewCount", result.viewCount);

                if (result.audioStreamsJson != null) {
                    jsonObject.put("audioStreams", result.audioStreamsJson);
                }
                if (result.videoStreamsJson != null) {
                    jsonObject.put("videoStreams", result.videoStreamsJson);
                }
                if (result.dashManifestUrl != null && !result.dashManifestUrl.isEmpty()) {
                    jsonObject.put("dashManifestUrl", result.dashManifestUrl);
                }
                jsonArray.put(jsonObject);
            }

            final Intent responseIntent = new Intent(SearchReceiver.ACTION_SEARCH_RESPONSE);
            responseIntent.putExtra(SearchReceiver.EXTRA_SUCCESS, true);
            responseIntent.putExtra(SearchReceiver.EXTRA_RESULTS, jsonArray.toString());

            if (resultReceiver != null) {
                responseIntent.setPackage(resultReceiver);
            }

            sendBroadcast(responseIntent);

        } catch (final JSONException e) {
            sendErrorBroadcast(resultReceiver, "JSON serialization error: " + e.getMessage());
        }
    }

    private void sendErrorBroadcast(@Nullable final String resultReceiver, final String error) {
        final Intent responseIntent = new Intent(SearchReceiver.ACTION_SEARCH_RESPONSE);
        responseIntent.putExtra(SearchReceiver.EXTRA_SUCCESS, false);
        responseIntent.putExtra(SearchReceiver.EXTRA_ERROR, error);

        if (resultReceiver != null) {
            responseIntent.setPackage(resultReceiver);
        }

        sendBroadcast(responseIntent);
    }

    private Notification buildNotification(final String contentText) {
        return new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("NewPipe API")
                .setContentText(contentText)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        final NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }

        final NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "NewPipe API Search",
                NotificationManager.IMPORTANCE_LOW);
        manager.createNotificationChannel(channel);
    }

    private List<SearchResult> limitToThree(final List<SearchResult> results) {
        if (results.size() <= 3) {
            return results;
        }

        return new ArrayList<>(results.subList(0, 3));
    }

    private List<SearchResult> enrichFirstWithStreams(final int serviceId,
                                                      final List<SearchResult> results)
            throws Exception {
        if (results.isEmpty()) {
            return results;
        }

        final SearchResult first = results.get(0);
        final StreamInfo info = StreamInfo.getInfo(NewPipe.getService(serviceId), first.url);

        first.audioStreamsJson = toAudioStreamsJson(info.getAudioStreams());
        first.videoStreamsJson = toVideoStreamsJson(info.getVideoStreams(), info.getVideoOnlyStreams());
        
        // Add DASH manifest URL for ExoPlayer compatibility
        String dashUrl = info.getDashMpdUrl();
        Log.d(TAG, "DASH URL found: " + (dashUrl != null ? dashUrl : "null"));
        Log.d(TAG, "Has audio streams: " + (info.getAudioStreams() != null && !info.getAudioStreams().isEmpty()));
        Log.d(TAG, "Has video streams: " + (info.getVideoStreams() != null && !info.getVideoStreams().isEmpty()));
        
        if (dashUrl != null && !dashUrl.isEmpty()) {
            first.dashManifestUrl = dashUrl;
            Log.d(TAG, "DASH URL set: " + dashUrl);
        } else {
            Log.w(TAG, "No DASH manifest URL available for this video");
            // Create synthetic DASH manifest for ExoPlayer using available streams
            first.dashManifestUrl = createSyntheticDashManifest(info);
            Log.d(TAG, "Synthetic DASH manifest created");
        }

        return results;
    }

    private String createSyntheticDashManifest(final StreamInfo info) {
        try {
            // Get best audio and video streams
            String bestAudioUrl = null;
            String bestVideoUrl = null;
            
            if (info.getAudioStreams() != null && !info.getAudioStreams().isEmpty()) {
                bestAudioUrl = info.getAudioStreams().get(0).getContent();
            }
            
            if (info.getVideoStreams() != null && !info.getVideoStreams().isEmpty()) {
                bestVideoUrl = info.getVideoStreams().get(0).getContent();
            }
            
            if (bestAudioUrl == null && bestVideoUrl == null) {
                return "";
            }
            
            // Create simple DASH manifest
            StringBuilder manifest = new StringBuilder();
            manifest.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            manifest.append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" mediaPresentationDuration=\"PT");
            manifest.append(info.getDuration() > 0 ? info.getDuration() : "0");
            manifest.append("S\" minBufferTime=\"PT1.5S\" type=\"static\">\n");
            manifest.append("  <Period>\n");
            
            if (bestVideoUrl != null) {
                manifest.append("    <AdaptationSet mimeType=\"video/mp4\">\n");
                manifest.append("      <Representation bandwidth=\"1000000\" codecs=\"avc1.42E01E\" ");
                manifest.append("width=\"1280\" height=\"720\" frameRate=\"30\">\n");
                manifest.append("        <BaseURL>").append(bestVideoUrl).append("</BaseURL>\n");
                manifest.append("        <SegmentBase indexRange=\"0-999\" mediaRange=\"0-999\" />\n");
                manifest.append("      </Representation>\n");
                manifest.append("    </AdaptationSet>\n");
            }
            
            if (bestAudioUrl != null) {
                manifest.append("    <AdaptationSet mimeType=\"audio/mp4\">\n");
                manifest.append("      <Representation bandwidth=\"128000\" codecs=\"mp4a.40.2\" ");
                manifest.append("audioSamplingRate=\"44100\" startWithSAP=\"1\">\n");
                manifest.append("        <BaseURL>").append(bestAudioUrl).append("</BaseURL>\n");
                manifest.append("        <SegmentBase indexRange=\"0-999\" mediaRange=\"0-999\" />\n");
                manifest.append("      </Representation>\n");
                manifest.append("    </AdaptationSet>\n");
            }
            
            manifest.append("  </Period>\n");
            manifest.append("</MPD>");
            
            // Convert to data URL (simplified version)
            return "data:application/dash+xml;charset=utf-8," + 
                   java.net.URLEncoder.encode(manifest.toString(), "UTF-8");
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to create synthetic DASH manifest", e);
            return "";
        }
    }

    private JSONArray toAudioStreamsJson(@Nullable final List<AudioStream> streams) throws JSONException {
        final JSONArray array = new JSONArray();
        if (streams == null) {
            return array;
        }

        for (final AudioStream stream : streams) {
            final JSONObject obj = new JSONObject();
            obj.put("url", stream.getContent());
            obj.put("mimeType", String.valueOf(stream.getFormat()));
            obj.put("bitrate", stream.getAverageBitrate());
            array.put(obj);
        }

        return array;
    }

    private JSONArray toVideoStreamsJson(@Nullable final List<VideoStream> streams,
                                         @Nullable final List<VideoStream> videoOnlyStreams)
            throws JSONException {
        final JSONArray array = new JSONArray();

        if (streams != null) {
            for (final VideoStream stream : streams) {
                final JSONObject obj = new JSONObject();
                obj.put("url", stream.getContent());
                obj.put("mimeType", String.valueOf(stream.getFormat()));
                obj.put("resolution", stream.getResolution());
                obj.put("videoOnly", false);
                array.put(obj);
            }
        }

        if (videoOnlyStreams != null) {
            for (final VideoStream stream : videoOnlyStreams) {
                final JSONObject obj = new JSONObject();
                obj.put("url", stream.getContent());
                obj.put("mimeType", String.valueOf(stream.getFormat()));
                obj.put("resolution", stream.getResolution());
                obj.put("videoOnly", true);
                array.put(obj);
            }
        }

        return array;
    }

    private static final class SearchResult extends SearchReceiver.VideoUrlResult {
        @Nullable
        private JSONArray audioStreamsJson;
        @Nullable
        private JSONArray videoStreamsJson;
        @Nullable
        private String dashManifestUrl;
    }
}

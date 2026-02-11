package org.schabi.newpipe.external;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class SearchReceiver extends BroadcastReceiver {
    private static final String TAG = "SearchReceiver";

    public static final String ACTION_SEARCH_REQUEST = "org.schabi.newpipe.SEARCH_REQUEST";
    public static final String ACTION_SEARCH_RESPONSE = "org.schabi.newpipe.SEARCH_RESPONSE";

    public static final String EXTRA_QUERY = "query";
    public static final String EXTRA_SERVICE_ID = "service_id";
    public static final String EXTRA_RESULT_RECEIVER = "result_receiver";
    public static final String EXTRA_INCLUDE_STREAM_URLS = "include_stream_urls";

    public static final String EXTRA_RESULTS = "results";
    public static final String EXTRA_ERROR = "error";
    public static final String EXTRA_SUCCESS = "success";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (intent == null) {
            return;
        }

        if (ACTION_SEARCH_REQUEST.equals(intent.getAction())) {
            Log.d(TAG, "Received SEARCH_REQUEST broadcast");
            handleSearchRequest(context, intent);
        }
    }

    private void handleSearchRequest(final Context context, final Intent intent) {
        final String query = intent.getStringExtra(EXTRA_QUERY);
        final int serviceId = intent.getIntExtra(EXTRA_SERVICE_ID, 0);

        if (query == null || query.trim().isEmpty()) {
            sendErrorResponse(context, intent, "Query parameter is missing or empty");
            return;
        }

        Log.d(TAG, "Handling search request query='" + query + "' serviceId=" + serviceId);
        performSearch(context, intent, query.trim(), serviceId);
    }

    private void performSearch(final Context context,
                               final Intent originalIntent,
                               final String query,
                               final int serviceId) {
        Log.d(TAG, "Starting search query='" + query + "' serviceId=" + serviceId);
        final Single<List<VideoUrlResult>> searchSingle = Single
                .fromCallable(() -> {
                    final StreamingService service = NewPipe.getService(serviceId);
                    return SearchInfo.getInfo(service,
                            service.getSearchQHFactory().fromQuery(
                                    query,
                                    Collections.emptyList(),
                                    ""));
                })
                .map(this::extractVideoUrls)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());

        searchSingle.subscribe(
                results -> {
                    Log.d(TAG, "Search success results=" + results.size());
                    sendSuccessResponse(context, originalIntent, results);
                },
                error -> {
                    final String message = error.getMessage() != null
                            ? error.getMessage()
                            : "Unknown error";
                    Log.e(TAG, "Search error: " + message, error);
                    sendErrorResponse(context, originalIntent, message);
                }
        );
    }

    private List<VideoUrlResult> extractVideoUrls(final SearchInfo searchInfo) throws Exception {
        final List<VideoUrlResult> results = new ArrayList<>();

        final List<InfoItem> items = searchInfo.getRelatedItems();
        if (items == null) {
            return results;
        }

        for (final InfoItem item : items) {
            if (item instanceof StreamInfoItem) {
                final StreamInfoItem streamItem = (StreamInfoItem) item;
                final VideoUrlResult result = new VideoUrlResult();

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

    private String chooseFirstImageUrl(final List<Image> images) {
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

    private void sendSuccessResponse(final Context context,
                                     final Intent originalIntent,
                                     final List<VideoUrlResult> results) {
        try {
            final JSONArray jsonArray = new JSONArray();
            for (final VideoUrlResult result : results) {
                final JSONObject jsonObject = new JSONObject();
                jsonObject.put("title", result.title);
                jsonObject.put("url", result.url);
                jsonObject.put("thumbnailUrl", result.thumbnailUrl);
                jsonObject.put("uploaderName", result.uploaderName);
                jsonObject.put("duration", result.duration);
                jsonObject.put("viewCount", result.viewCount);
                jsonArray.put(jsonObject);
            }

            final Intent responseIntent = new Intent(ACTION_SEARCH_RESPONSE);
            responseIntent.putExtra(EXTRA_SUCCESS, true);
            responseIntent.putExtra(EXTRA_RESULTS, jsonArray.toString());

            final String resultReceiver = originalIntent.getStringExtra(EXTRA_RESULT_RECEIVER);
            if (resultReceiver != null) {
                responseIntent.setPackage(resultReceiver);
            }

            context.sendBroadcast(responseIntent);

        } catch (final JSONException e) {
            sendErrorResponse(context, originalIntent, "JSON serialization error: " + e.getMessage());
        }
    }

    private void sendErrorResponse(final Context context,
                                   final Intent originalIntent,
                                   final String errorMessage) {
        final Intent responseIntent = new Intent(ACTION_SEARCH_RESPONSE);
        responseIntent.putExtra(EXTRA_SUCCESS, false);
        responseIntent.putExtra(EXTRA_ERROR, errorMessage);

        final String resultReceiver = originalIntent.getStringExtra(EXTRA_RESULT_RECEIVER);
        if (resultReceiver != null) {
            responseIntent.setPackage(resultReceiver);
        }

        context.sendBroadcast(responseIntent);
        Log.e(TAG, "Error: " + errorMessage);
    }

    public static class VideoUrlResult {
        public String title;
        public String url;
        public String thumbnailUrl;
        public String uploaderName;
        public long duration;
        public long viewCount;
    }
}

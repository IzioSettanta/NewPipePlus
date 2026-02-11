package org.schabi.newpipe.external;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.util.ExtractorHelper;
import org.schabi.newpipe.util.image.ImageStrategy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * BroadcastReceiver per ricevere richieste di ricerca da applicazioni esterne come Kodular.
 * Restituisce le URL dei video (audio e video) in base a una parola di ricerca.
 */
public class SearchReceiver extends BroadcastReceiver {
    private static final String TAG = "SearchReceiver";
    
    // Azioni custom per la comunicazione con app esterne
    public static final String ACTION_SEARCH_REQUEST = "org.schabi.newpipe.SEARCH_REQUEST";
    public static final String ACTION_SEARCH_RESPONSE = "org.schabi.newpipe.SEARCH_RESPONSE";
    
    // Parametri per la richiesta
    public static final String EXTRA_QUERY = "query";
    public static final String EXTRA_SERVICE_ID = "service_id";
    public static final String EXTRA_RESULT_RECEIVER = "result_receiver";
    
    // Parametri per la risposta
    public static final String EXTRA_RESULTS = "results";
    public static final String EXTRA_ERROR = "error";
    public static final String EXTRA_SUCCESS = "success";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (ACTION_SEARCH_REQUEST.equals(intent.getAction())) {
            handleSearchRequest(context, intent);
        }
    }

    private void handleSearchRequest(final Context context, final Intent intent) {
        final String query = intent.getStringExtra(EXTRA_QUERY);
        final int serviceId = intent.getIntExtra(EXTRA_SERVICE_ID, 0); // 0 = YouTube

        if (query == null || query.trim().isEmpty()) {
            sendErrorResponse(context, intent, "Query parameter is missing or empty");
            return;
        }

        Log.d(TAG, "Ricevuta richiesta di ricerca: " + query + " per servizio: " + serviceId);

        // Esegui la ricerca in background
        performSearch(context, intent, query.trim(), serviceId);
    }

    private void performSearch(final Context context, final Intent originalIntent,
                               final String query, final int serviceId) {
        try {
            final StreamingService service = NewPipe.getService(serviceId);

            final Single<List<VideoUrlResult>> searchSingle = ExtractorHelper
                    .searchFor(serviceId, query, Arrays.asList(), "")
                    .map(this::extractVideoUrls)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread());

            final Disposable disposable = searchSingle.subscribe(
                    results -> sendSuccessResponse(context, originalIntent, results),
                    error -> sendErrorResponse(context, originalIntent, error.getMessage())
            );

        } catch (final ExtractionException e) {
            sendErrorResponse(context, originalIntent, "Service extraction error: " + e.getMessage());
        }
    }

    private List<VideoUrlResult> extractVideoUrls(final SearchInfo searchInfo) throws Exception {
        final List<VideoUrlResult> results = new ArrayList<>();

        if (searchInfo.getRelatedItems() != null) {
            for (final InfoItem item : searchInfo.getRelatedItems()) {
                if (item instanceof StreamInfoItem) {
                    final StreamInfoItem streamItem = (StreamInfoItem) item;
                    final VideoUrlResult result = new VideoUrlResult();
                    
                    result.title = streamItem.getName();
                    result.url = streamItem.getUrl();
                    result.thumbnailUrl = ImageStrategy.choosePreferredImage(streamItem.getThumbnails());
                    result.uploaderName = streamItem.getUploaderName();
                    result.duration = streamItem.getDuration();
                    result.viewCount = streamItem.getViewCount();
                    
                    results.add(result);
                }
            }
        }
        return results;
    }

    private void sendSuccessResponse(final Context context, final Intent originalIntent, final List<VideoUrlResult> results) {
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

            // Se è specificato un result receiver, invia direttamente a quel receiver
            final String resultReceiver = originalIntent.getStringExtra(EXTRA_RESULT_RECEIVER);
            if (resultReceiver != null) {
                responseIntent.setPackage(resultReceiver);
            }

            context.sendBroadcast(responseIntent);
            Log.d(TAG, "Inviata risposta con " + results.size() + " risultati");

        } catch (final JSONException e) {
            sendErrorResponse(context, originalIntent, "JSON serialization error: " + e.getMessage());
        }
    }

    private void sendErrorResponse(final Context context, final Intent originalIntent, final String errorMessage) {
        final Intent responseIntent = new Intent(ACTION_SEARCH_RESPONSE);
        responseIntent.putExtra(EXTRA_SUCCESS, false);
        responseIntent.putExtra(EXTRA_ERROR, errorMessage);

        final String resultReceiver = originalIntent.getStringExtra(EXTRA_RESULT_RECEIVER);
        if (resultReceiver != null) {
            responseIntent.setPackage(resultReceiver);
        }

        context.sendBroadcast(responseIntent);
        Log.e(TAG, "Inviata risposta di errore: " + errorMessage);
    }

    /**
     * Classe helper per contenere i risultati della ricerca.
     */
    public static class VideoUrlResult {
        public String title;
        public String url;
        public String thumbnailUrl;
        public String uploaderName;
        public long duration;
        public long viewCount;
    }
}

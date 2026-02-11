package org.schabi.newpipe.external;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ResultReceiver;
import android.util.Log;

import androidx.annotation.Nullable;

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
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Servizio per gestire richieste di ricerca da applicazioni esterne.
 * Fornisce un'API più robusta rispetto al semplice BroadcastReceiver.
 */
public class SearchService extends Service {
    private static final String TAG = "SearchService";
    
    // Azioni per il servizio
    public static final String ACTION_SEARCH = "org.schabi.newpipe.action.SEARCH";
    
    // Parametri
    public static final String EXTRA_QUERY = "query";
    public static final String EXTRA_SERVICE_ID = "service_id";
    public static final String EXTRA_RESULT_RECEIVER = "result_receiver";
    public static final String EXTRA_MAX_RESULTS = "max_results";
    
    // Codici di risultato
    public static final int RESULT_CODE_SUCCESS = 0;
    public static final int RESULT_CODE_ERROR = 1;
    
    // Chiavi per i dati del risultato
    public static final String RESULT_KEY_RESULTS = "results";
    public static final String RESULT_KEY_ERROR = "error";
    public static final String RESULT_KEY_COUNT = "count";

    private Disposable searchDisposable;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "SearchService creato");
    }

    @Override
    public int onStartCommand(@Nullable final Intent intent, final int flags, final int startId) {
        if (intent != null && ACTION_SEARCH.equals(intent.getAction())) {
            handleSearchRequest(intent);
        }
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(final Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (searchDisposable != null && !searchDisposable.isDisposed()) {
            searchDisposable.dispose();
        }
        Log.d(TAG, "SearchService distrutto");
    }

    private void handleSearchRequest(final Intent intent) {
        final String query = intent.getStringExtra(EXTRA_QUERY);
        final int serviceId = intent.getIntExtra(EXTRA_SERVICE_ID, 0); // Default YouTube
        final int maxResults = intent.getIntExtra(EXTRA_MAX_RESULTS, 20); // Default 20 risultati
        final ResultReceiver resultReceiver = intent.getParcelableExtra(EXTRA_RESULT_RECEIVER);

        if (query == null || query.trim().isEmpty()) {
            sendErrorResult(resultReceiver, "Query parameter is missing or empty");
            return;
        }

        Log.d(TAG, "Avvio ricerca: '" + query + "' per servizio " + serviceId + ", max " + maxResults + " risultati");

        performSearch(query.trim(), serviceId, maxResults, resultReceiver);
    }

    private void performSearch(final String query, final int serviceId, final int maxResults, final ResultReceiver resultReceiver) {
        try {
            final StreamingService service = NewPipe.getService(serviceId);

            searchDisposable = ExtractorHelper.searchFor(serviceId, query, Arrays.asList(), "")
                    .map(searchInfo -> extractVideoUrls(searchInfo, maxResults))
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                            results -> sendSuccessResult(resultReceiver, results),
                            error -> sendErrorResult(resultReceiver, "Search failed: " + error.getMessage())
                    );

        } catch (final ExtractionException e) {
            sendErrorResult(resultReceiver, "Service extraction error: " + e.getMessage());
        }
    }

    private List<VideoUrlResult> extractVideoUrls(final SearchInfo searchInfo, final int maxResults) throws Exception {
        final List<VideoUrlResult> results = new ArrayList<>();

        if (searchInfo.getRelatedItems() != null) {
            int count = 0;
            for (final InfoItem item : searchInfo.getRelatedItems()) {
                if (count >= maxResults) {
                    break;
                }

                if (item instanceof StreamInfoItem) {
                    final StreamInfoItem streamItem = (StreamInfoItem) item;
                    final VideoUrlResult result = new VideoUrlResult();

                    result.title = streamItem.getName();
                    result.url = streamItem.getUrl();
                    result.thumbnailUrl = ImageStrategy.choosePreferredImage(
                            streamItem.getThumbnails());
                    result.uploaderName = streamItem.getUploaderName();
                    result.duration = streamItem.getDuration();
                    result.viewCount = streamItem.getViewCount();
                    result.uploadDate = streamItem.getTextualUploadDate();

                    results.add(result);
                    count++;
                }
            }
        }

        return results;
    }

    private void sendSuccessResult(final ResultReceiver resultReceiver, final List<VideoUrlResult> results) {
        if (resultReceiver == null) {
            Log.w(TAG, "ResultReceiver è null, impossibile inviare il risultato");
            return;
        }

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
                jsonObject.put("uploadDate", result.uploadDate);
                jsonArray.put(jsonObject);
            }

            final Bundle bundle = new Bundle();
            bundle.putString(RESULT_KEY_RESULTS, jsonArray.toString());
            bundle.putInt(RESULT_KEY_COUNT, results.size());

            resultReceiver.send(RESULT_CODE_SUCCESS, bundle);
            Log.d(TAG, "Inviati " + results.size() + " risultati con successo");

        } catch (final JSONException e) {
            sendErrorResult(resultReceiver, "JSON serialization error: " + e.getMessage());
        }
    }

    private void sendErrorResult(final ResultReceiver resultReceiver, final String errorMessage) {
        if (resultReceiver == null) {
            Log.w(TAG, "ResultReceiver è null, impossibile inviare l'errore: " + errorMessage);
            return;
        }

        final Bundle bundle = new Bundle();
        bundle.putString(RESULT_KEY_ERROR, errorMessage);

        resultReceiver.send(RESULT_CODE_ERROR, bundle);
        Log.e(TAG, "Inviato errore: " + errorMessage);
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
        public String uploadDate;
    }
}

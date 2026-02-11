package org.schabi.newpipe.external;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * API helper per facilitare l'integrazione con NewPipe da applicazioni esterne come Kodular.
 * Fornisce metodi semplici per eseguire ricerche e ottenere i risultati.
 */
public final class NewPipeSearchAPI {
    private static final String TAG = "NewPipeSearchAPI";

    // Timeout per le operazioni (in millisecondi)
    private static final int DEFAULT_TIMEOUT = 30000; // 30 secondi

    // ID dei servizi supportati
    public static final int SERVICE_YOUTUBE = 0;
    public static final int SERVICE_SOUNDCLOUD = 1;
    public static final int SERVICE_MEDIA_CCC = 2;
    public static final int SERVICE_PEER_TUBE = 3;
    public static final int SERVICE_BANDCAMP = 4;

    /**
     * Costruttore privato per classe utility.
     */
    private NewPipeSearchAPI() {
        // Utility class
    }

    /**
     * Interfaccia per i callback dei risultati della ricerca.
     */
    public interface SearchCallback {
        void onSuccess(List<VideoResult> results);
        void onError(String error);
    }

    /**
     * Classe che rappresenta un risultato della ricerca.
     */
    public static class VideoResult {
        public String title;
        public String url;
        public String thumbnailUrl;
        public String uploaderName;
        public long duration;
        public long viewCount;
        public String uploadDate;

        @Override
        public String toString() {
            return "VideoResult{"
                    + "title='" + title + '\''
                    + ", url='" + url + '\''
                    + ", uploader='" + uploaderName + '\''
                    + ", duration=" + duration
                    + "}";
        }
    }

    /**
     * Esegue una ricerca utilizzando il BroadcastReceiver (metodo semplice).
     *
     * @param context Context dell'applicazione chiamante
     * @param query Termine di ricerca
     * @param callback Callback per ricevere i risultati
     */
    public static void searchWithBroadcast(final Context context, final String query, final SearchCallback callback) {
        searchWithBroadcast(context, query, SERVICE_YOUTUBE, callback);
    }

    /**
     * Esegue una ricerca utilizzando il BroadcastReceiver (metodo semplice).
     * 
     * @param context Context dell'applicazione chiamante
     * @param query Termine di ricerca
     * @param serviceId ID del servizio da utilizzare
     * @param callback Callback per ricevere i risultati
     */
    public static void searchWithBroadcast(final Context context, final String query, final int serviceId, final SearchCallback callback) {
        if (query == null || query.trim().isEmpty()) {
            callback.onError("Query cannot be empty");
            return;
        }

        // Registra un receiver temporaneo per la risposta
        final BroadcastReceiver responseReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(final Context context, final Intent intent) {
                try {
                    context.unregisterReceiver(this);

                    final boolean success = intent.getBooleanExtra(SearchReceiver.EXTRA_SUCCESS, false);
                    if (success) {
                        final String resultsJson = intent.getStringExtra(SearchReceiver.EXTRA_RESULTS);
                        final List<VideoResult> results = parseResultsJson(resultsJson);
                        callback.onSuccess(results);
                    } else {
                        final String error = intent.getStringExtra(SearchReceiver.EXTRA_ERROR);
                        callback.onError(error != null ? error : "Unknown error");
                    }
                } catch (final Exception e) {
                    callback.onError("Error processing response: " + e.getMessage());
                }
            }
        };

        // Registra il receiver per la risposta
        context.registerReceiver(responseReceiver, new IntentFilter(SearchReceiver.ACTION_SEARCH_RESPONSE));

        // Invia la richiesta di ricerca
        final Intent searchIntent = new Intent(SearchReceiver.ACTION_SEARCH_REQUEST);
        searchIntent.putExtra(SearchReceiver.EXTRA_QUERY, query);
        searchIntent.putExtra(SearchReceiver.EXTRA_SERVICE_ID, serviceId);
        searchIntent.putExtra(SearchReceiver.EXTRA_RESULT_RECEIVER, context.getPackageName());

        context.sendBroadcast(searchIntent);

        Log.d(TAG, "Inviata richiesta broadcast per: " + query);
    }

    /**
     * Esegue una ricerca utilizzando il Service (metodo più robusto).
     * 
     * @param context Context dell'applicazione chiamante
     * @param query Termine di ricerca
     * @param callback Callback per ricevere i risultati
     */
    public static void searchWithService(final Context context, final String query, final SearchCallback callback) {
        searchWithService(context, query, SERVICE_YOUTUBE, 20, callback);
    }

    /**
     * Esegue una ricerca utilizzando il Service (metodo più robusto).
     * 
     * @param context Context dell'applicazione chiamante
     * @param query Termine di ricerca
     * @param serviceId ID del servizio da utilizzare
     * @param maxResults Numero massimo di risultati
     * @param callback Callback per ricevere i risultati
     */
    public static void searchWithService(final Context context, final String query, final int serviceId, final int maxResults, final SearchCallback callback) {
        if (query == null || query.trim().isEmpty()) {
            callback.onError("Query cannot be empty");
            return;
        }

        final ResultReceiver resultReceiver = new ResultReceiver(new Handler(Looper.getMainLooper())) {
            @Override
            protected void onReceiveResult(final int resultCode, final Bundle resultData) {
                if (resultCode == SearchService.RESULT_CODE_SUCCESS) {
                    try {
                        final String resultsJson = resultData.getString(
                                SearchService.RESULT_KEY_RESULTS);
                        final int count = resultData.getInt(
                                SearchService.RESULT_KEY_COUNT, 0);
                        final List<VideoResult> results = parseResultsJson(resultsJson);
                        Log.d(TAG, "Ricevuti " + count + " risultati dal servizio");
                        callback.onSuccess(results);
                    } catch (final Exception e) {
                        callback.onError("Error parsing results: " + e.getMessage());
                    }
                } else if (resultCode == SearchService.RESULT_CODE_ERROR) {
                    final String error = resultData.getString(
                                SearchService.RESULT_KEY_ERROR);
                    callback.onError(error != null ? error : "Unknown error");
                }
            }
        };

        final Intent serviceIntent = new Intent(context, SearchService.class);
        serviceIntent.setAction(SearchService.ACTION_SEARCH);
        serviceIntent.putExtra(SearchService.EXTRA_QUERY, query);
        serviceIntent.putExtra(SearchService.EXTRA_SERVICE_ID, serviceId);
        serviceIntent.putExtra(SearchService.EXTRA_MAX_RESULTS, maxResults);
        serviceIntent.putExtra(SearchService.EXTRA_RESULT_RECEIVER, resultReceiver);

        context.startService(serviceIntent);

        Log.d(TAG, "Avviato servizio per ricerca: " + query);
    }

    /**
     * Esegue una ricerca sincrona (bloccante) - non consigliato per il thread principale.
     * 
     * @param context Context dell'applicazione chiamante
     * @param query Termine di ricerca
     * @return Lista di risultati o null in caso di errore
     */
    public static List<VideoResult> searchSync(final Context context, final String query) {
        return searchSync(context, query, SERVICE_YOUTUBE, 20, DEFAULT_TIMEOUT);
    }

    /**
     * Esegue una ricerca sincrona (bloccante) - non consigliato per il thread principale.
     * 
     * @param context Context dell'applicazione chiamante
     * @param query Termine di ricerca
     * @param serviceId ID del servizio da utilizzare
     * @param maxResults Numero massimo di risultati
     * @param timeoutMs Timeout in millisecondi
     * @return Lista di risultati o null in caso di errore
     */
    public static List<VideoResult> searchSync(final Context context, final String query, final int serviceId, final int maxResults, final int timeoutMs) {
        final CountDownLatch latch = new CountDownLatch(1);
        final List<VideoResult>[] results = new List[]{null};
        final String[] error = new String[]{null};

        searchWithService(context, query, serviceId, maxResults, new SearchCallback() {
            @Override
            public void onSuccess(final List<VideoResult> result) {
                results[0] = result;
                latch.countDown();
            }

            @Override
            public void onError(final String errorMessage) {
                error[0] = errorMessage;
                latch.countDown();
            }
        });

        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                Log.e(TAG, "Timeout durante la ricerca sincrona");
                return null;
            }

            if (error[0] != null) {
                Log.e(TAG, "Errore nella ricerca sincrona: " + error[0]);
                return null;
            }

            return results[0];
        } catch (final InterruptedException e) {
            Log.e(TAG, "Ricerca sincrona interrotta", e);
            return null;
        }
    }

    /**
     * Converte una stringa JSON in una lista di VideoResult.
     *
     * @param json Stringa JSON da convertire
     * @return Lista di VideoResult
     * @throws JSONException Se il JSON non è valido
     */
    private static List<VideoResult> parseResultsJson(final String json) throws JSONException {
        final List<VideoResult> results = new ArrayList<>();

        if (json == null || json.isEmpty()) {
            return results;
        }

        final JSONArray jsonArray = new JSONArray(json);
        for (int i = 0; i < jsonArray.length(); i++) {
            final JSONObject jsonObject = jsonArray.getJSONObject(i);
            final VideoResult result = new VideoResult();

            result.title = jsonObject.optString("title", "");
            result.url = jsonObject.optString("url", "");
            result.thumbnailUrl = jsonObject.optString("thumbnailUrl", "");
            result.uploaderName = jsonObject.optString("uploaderName", "");
            result.duration = jsonObject.optLong("duration", -1);
            result.viewCount = jsonObject.optLong("viewCount", -1);
            result.uploadDate = jsonObject.optString("uploadDate", "");

            results.add(result);
        }

        return results;
    }

    /**
     * Restituisce il nome del servizio dato il suo ID.
     *
     * @param serviceId ID del servizio
     * @return Nome del servizio
     */
    public static String getServiceName(final int serviceId) {
        switch (serviceId) {
            case SERVICE_YOUTUBE:
                return "YouTube";
            case SERVICE_SOUNDCLOUD:
                return "SoundCloud";
            case SERVICE_MEDIA_CCC:
                return "Media CCC";
            case SERVICE_PEER_TUBE:
                return "PeerTube";
            case SERVICE_BANDCAMP:
                return "Bandcamp";
            default:
                return "Unknown";
        }
    }
}

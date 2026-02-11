package org.schabi.newpipe.external;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class TestSearchActivity extends AppCompatActivity {

    private static final String TAG = "TestSearchActivity";

    private EditText queryEditText;
    private Button searchButton;
    private TextView resultsTextView;
    private TextView statusTextView;

    private BroadcastReceiver responseReceiver;
    private boolean isWaitingForResponse;

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        createLayout();
        setupBroadcastReceiver();

        searchButton.setOnClickListener(v -> performBroadcastSearch());
    }

    private void createLayout() {
        queryEditText = new EditText(this);
        queryEditText.setHint("Inserisci termine di ricerca...");
        queryEditText.setPadding(16, 16, 16, 16);

        searchButton = new Button(this);
        searchButton.setText("Cerca (Broadcast)");
        searchButton.setPadding(16, 16, 16, 16);

        statusTextView = new TextView(this);
        statusTextView.setText("Pronto per la ricerca");
        statusTextView.setPadding(16, 16, 16, 16);

        resultsTextView = new TextView(this);
        resultsTextView.setText("I risultati appariranno qui...");
        resultsTextView.setPadding(16, 16, 16, 16);
        resultsTextView.setMovementMethod(LinkMovementMethod.getInstance());

        final android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.addView(resultsTextView);

        final android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.addView(queryEditText);
        layout.addView(searchButton);
        layout.addView(statusTextView);
        layout.addView(scrollView);

        setContentView(layout);
    }

    private void setupBroadcastReceiver() {
        responseReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(final Context context, final Intent intent) {
                if (SearchReceiver.ACTION_SEARCH_RESPONSE.equals(intent.getAction())) {
                    handleBroadcastResponse(intent);
                }
            }
        };
    }

    private void performBroadcastSearch() {
        final String query = queryEditText.getText().toString().trim();
        if (query.isEmpty()) {
            Toast.makeText(this, "Inserisci un termine di ricerca", Toast.LENGTH_SHORT).show();
            return;
        }

        if (isWaitingForResponse) {
            Toast.makeText(this, "Ricerca già in corso...", Toast.LENGTH_SHORT).show();
            return;
        }

        isWaitingForResponse = true;
        statusTextView.setText("Ricerca in corso...");
        resultsTextView.setText("");

        registerReceiver(responseReceiver, new IntentFilter(SearchReceiver.ACTION_SEARCH_RESPONSE));

        final Intent searchIntent = new Intent(ApiSearchService.ACTION_SEARCH);
        searchIntent.setClass(this, ApiSearchService.class);
        searchIntent.putExtra(SearchReceiver.EXTRA_QUERY, query);
        searchIntent.putExtra(SearchReceiver.EXTRA_SERVICE_ID, 0);
        searchIntent.putExtra(SearchReceiver.EXTRA_RESULT_RECEIVER, getPackageName());
        searchIntent.putExtra(SearchReceiver.EXTRA_INCLUDE_STREAM_URLS, true);

        Log.d(TAG, "Starting foreground service search request for: " + query);
        startForegroundService(searchIntent);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (isWaitingForResponse) {
                isWaitingForResponse = false;
                statusTextView.setText("Timeout della ricerca");
                Log.w(TAG, "Search timeout");
                safeUnregisterReceiver();
            }
        }, 30000);
    }

    private void handleBroadcastResponse(final Intent intent) {
        if (!isWaitingForResponse) {
            return;
        }

        isWaitingForResponse = false;
        safeUnregisterReceiver();

        final boolean success = intent.getBooleanExtra(SearchReceiver.EXTRA_SUCCESS, false);
        if (success) {
            final String resultsJson = intent.getStringExtra(SearchReceiver.EXTRA_RESULTS);
            final List<VideoResult> results = parseResultsJson(resultsJson);
            statusTextView.setText("Ricerca completata: " + results.size() + " risultati");
            displayResults(results);
        } else {
            final String error = intent.getStringExtra(SearchReceiver.EXTRA_ERROR);
            statusTextView.setText("Errore nella ricerca");
            resultsTextView.setText("Errore: " + (error != null ? error : "Unknown error"));
        }
    }

    private void displayResults(final List<VideoResult> results) {
        final SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = 0; i < results.size(); i++) {
            final VideoResult result = results.get(i);

            sb.append(String.valueOf(i + 1)).append(". ").append(result.title).append("\n");
            sb.append("   URL: ");
            appendLink(sb, result.url);
            sb.append("\n");
            sb.append("   Canale: ").append(result.uploaderName).append("\n");

            if (i == 0) {
                sb.append("\n");
                sb.append("   Audio streams: ")
                        .append(String.valueOf(result.audioStreamUrls.size()))
                        .append("\n");
                for (final String url : result.audioStreamUrls) {
                    sb.append("      ");
                    appendLink(sb, url);
                    sb.append("\n");
                }

                sb.append("\n");
                sb.append("   Video streams: ")
                        .append(String.valueOf(result.videoStreamUrls.size()))
                        .append("\n");
                for (final String url : result.videoStreamUrls) {
                    sb.append("      ");
                    appendLink(sb, url);
                    sb.append("\n");
                }

                // Display DASH manifest URL for ExoPlayer
                if (result.dashManifestUrl != null && !result.dashManifestUrl.isEmpty()) {
                    sb.append("\n");
                    sb.append("   DASH Manifest (ExoPlayer): ");
                    appendLink(sb, result.dashManifestUrl);
                    sb.append("\n");
                }
            }

            sb.append("\n");
        }

        resultsTextView.setText(sb);
    }

    private List<VideoResult> parseResultsJson(@Nullable final String json) {
        if (json == null) {
            return new ArrayList<>();
        }

        try {
            final JSONArray jsonArray = new JSONArray(json);
            final List<VideoResult> results = new ArrayList<>();

            for (int i = 0; i < jsonArray.length(); i++) {
                final JSONObject jsonObject = jsonArray.getJSONObject(i);
                final VideoResult result = new VideoResult();

                result.title = jsonObject.optString("title", "");
                result.url = jsonObject.optString("url", "");
                result.thumbnailUrl = jsonObject.optString("thumbnailUrl", "");
                result.uploaderName = jsonObject.optString("uploaderName", "");
                result.duration = jsonObject.optLong("duration", -1);
                result.viewCount = jsonObject.optLong("viewCount", -1);
                result.dashManifestUrl = jsonObject.optString("dashManifestUrl", "");
                
                Log.d(TAG, "Parsed DASH URL: " + (result.dashManifestUrl != null && !result.dashManifestUrl.isEmpty() ? result.dashManifestUrl : "empty"));

                final JSONArray audioStreams = jsonObject.optJSONArray("audioStreams");
                if (audioStreams != null) {
                    for (int j = 0; j < audioStreams.length(); j++) {
                        final JSONObject streamObj = audioStreams.optJSONObject(j);
                        if (streamObj != null) {
                            final String url = streamObj.optString("url", "");
                            if (!url.isEmpty()) {
                                result.audioStreamUrls.add(url);
                            }
                        }
                    }
                }

                final JSONArray videoStreams = jsonObject.optJSONArray("videoStreams");
                if (videoStreams != null) {
                    for (int j = 0; j < videoStreams.length(); j++) {
                        final JSONObject streamObj = videoStreams.optJSONObject(j);
                        if (streamObj != null) {
                            final String url = streamObj.optString("url", "");
                            if (url.isEmpty()) {
                                continue;
                            }
                            final String resolution = streamObj.optString("resolution", "");
                            final boolean videoOnly = streamObj.optBoolean("videoOnly", false);

                            final String label = (videoOnly ? "(video-only) " : "")
                                    + (resolution.isEmpty() ? "" : (resolution + " "))
                                    + url;
                            result.videoStreamUrls.add(label);
                        }
                    }
                }

                results.add(result);
            }

            return results;
        } catch (final JSONException e) {
            return new ArrayList<>();
        }
    }

    private void appendLink(final SpannableStringBuilder sb, final String url) {
        if (url == null || url.isEmpty()) {
            sb.append("(empty)");
            return;
        }

        final int start = sb.length();
        sb.append(url);
        final int end = sb.length();
        sb.setSpan(new ClickableSpan() {
            @Override
            public void onClick(final View widget) {
                openInChrome(url);
            }
        }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void openInChrome(final String url) {
        Log.d(TAG, "Attempting to open URL: " + url);
        
        if (url == null || url.trim().isEmpty()) {
            Log.e(TAG, "URL is null or empty");
            Toast.makeText(this, "URL non valido", Toast.LENGTH_SHORT).show();
            return;
        }

        // Extract clean URL from stream labels (remove prefixes like "(video-only) 1080p ")
        String cleanUrl = url.trim();
        if (cleanUrl.contains("http")) {
            int httpIndex = cleanUrl.indexOf("http");
            if (httpIndex > 0) {
                cleanUrl = cleanUrl.substring(httpIndex);
            }
        }
        
        // Handle DASH manifest URLs - they should not be opened in browsers
        if (cleanUrl.startsWith("data:application/dash+xml")) {
            Log.d(TAG, "DASH manifest detected - not suitable for browser opening");
            Toast.makeText(this, "DASH manifest per ExoPlayer - non apribile in browser", Toast.LENGTH_LONG).show();
            return;
        }

        Log.d(TAG, "Clean URL extracted: " + cleanUrl);

        try {
            final Uri uri = Uri.parse(cleanUrl);
            final Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            // Check available browsers first
            final String[] chromePackages = {
                "com.android.chrome",
                "com.chrome.beta",
                "com.chrome.dev",
                "com.chrome.canary"
            };
            
            boolean chromeFound = false;
            for (final String chromePackage : chromePackages) {
                if (isPackageInstalled(chromePackage)) {
                    try {
                        Log.d(TAG, "Trying Chrome package: " + chromePackage);
                        i.setPackage(chromePackage);
                        startActivity(i);
                        Log.d(TAG, "Successfully opened URL in Chrome: " + chromePackage);
                        chromeFound = true;
                        break;
                    } catch (final Exception e) {
                        Log.w(TAG, "Failed to open with " + chromePackage + ": " + e.getMessage());
                    }
                }
            }

            if (!chromeFound) {
                // Fallback to any browser
                Log.d(TAG, "Chrome not found, trying any available browser");
                final Intent fallback = new Intent(Intent.ACTION_VIEW, uri);
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                
                // Check if any browser can handle this
                if (fallback.resolveActivity(getPackageManager()) != null) {
                    final String packageName = fallback.resolveActivity(getPackageManager()).getPackageName();
                    Log.d(TAG, "Opening with browser: " + packageName);
                    startActivity(fallback);
                    Log.d(TAG, "Successfully opened URL with fallback browser");
                } else {
                    Log.e(TAG, "No browser available to handle the URL");
                    Toast.makeText(this, "Nessun browser disponibile", Toast.LENGTH_SHORT).show();
                }
            }
        } catch (final Exception e) {
            Log.e(TAG, "Failed to open URL: " + e.getMessage(), e);
            Toast.makeText(this, "Errore nell'aprire l'URL: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isPackageInstalled(final String packageName) {
        try {
            getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (final Exception e) {
            return false;
        }
    }

    private static final class VideoResult extends SearchReceiver.VideoUrlResult {
        private final List<String> audioStreamUrls = new ArrayList<>();
        private final List<String> videoStreamUrls = new ArrayList<>();
        private String dashManifestUrl;
    }

    private void safeUnregisterReceiver() {
        try {
            unregisterReceiver(responseReceiver);
        } catch (final Exception e) {
            // ignore
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        safeUnregisterReceiver();
    }
}

package org.schabi.newpipe.external;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * Activity di test per verificare il funzionamento dell'API di ricerca esterna.
 * Può essere utilizzata come riferimento per implementare l'integrazione in altre app.
 */
public class TestSearchActivity extends AppCompatActivity {
    private static final String TAG = "TestSearchActivity";
    
    private EditText queryEditText;
    private Button searchButton;
    private Button searchServiceButton;
    private TextView resultsTextView;
    private TextView statusTextView;
    
    private BroadcastReceiver responseReceiver;
    private boolean isWaitingForResponse = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Layout semplice creato programmaticamente
        createLayout();
        
        setupBroadcastReceiver();
        setupClickListeners();
    }

    private void createLayout() {
        // Creiamo un layout semplice per test
        queryEditText = new EditText(this);
        queryEditText.setHint("Inserisci termine di ricerca...");
        queryEditText.setPadding(16, 16, 16, 16);
        
        searchButton = new Button(this);
        searchButton.setText("Cerca (Broadcast)");
        searchButton.setPadding(16, 16, 16, 16);
        
        searchServiceButton = new Button(this);
        searchServiceButton.setText("Cerca (Service)");
        searchServiceButton.setPadding(16, 16, 16, 16);
        
        statusTextView = new TextView(this);
        statusTextView.setText("Pronto per la ricerca");
        statusTextView.setPadding(16, 16, 16, 16);
        
        resultsTextView = new TextView(this);
        resultsTextView.setText("I risultati appariranno qui...");
        resultsTextView.setPadding(16, 16, 16, 16);
        
        // Layout verticale
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.addView(queryEditText);
        layout.addView(searchButton);
        layout.addView(searchServiceButton);
        layout.addView(statusTextView);
        layout.addView(resultsTextView);
        
        setContentView(layout);
    }

    private void setupBroadcastReceiver() {
        responseReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (SearchReceiver.ACTION_SEARCH_RESPONSE.equals(intent.getAction())) {
                    handleBroadcastResponse(intent);
                }
            }
        };
    }

    private void setupClickListeners() {
        searchButton.setOnClickListener(v -> performBroadcastSearch());
        searchServiceButton.setOnClickListener(v -> performServiceSearch());
    }

    private void performBroadcastSearch() {
        String query = queryEditText.getText().toString().trim();
        if (query.isEmpty()) {
            Toast.makeText(this, "Inserisci un termine di ricerca", Toast.LENGTH_SHORT).show();
            return;
        }

        if (isWaitingForResponse) {
            Toast.makeText(this, "Ricerca già in corso...", Toast.LENGTH_SHORT).show();
            return;
        }

        isWaitingForResponse = true;
        statusTextView.setText("Ricerca in corso (Broadcast)...");
        resultsTextView.setText("");

        // Registra il receiver per la risposta
        IntentFilter filter = new IntentFilter(SearchReceiver.ACTION_SEARCH_RESPONSE);
        registerReceiver(responseReceiver, filter);

        // Invia la richiesta
        Intent searchIntent = new Intent(SearchReceiver.ACTION_SEARCH_REQUEST);
        searchIntent.putExtra(SearchReceiver.EXTRA_QUERY, query);
        searchIntent.putExtra(SearchReceiver.EXTRA_SERVICE_ID, NewPipeSearchAPI.SERVICE_YOUTUBE);
        searchIntent.putExtra(SearchReceiver.EXTRA_RESULT_RECEIVER, getPackageName());
        
        sendBroadcast(searchIntent);
        
        Log.d(TAG, "Inviata richiesta broadcast per: " + query);
        
        // Timeout dopo 30 secondi
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (isWaitingForResponse) {
                isWaitingForResponse = false;
                statusTextView.setText("Timeout della ricerca");
                try {
                    unregisterReceiver(responseReceiver);
                } catch (Exception e) {
                    // Receiver già non registrato
                }
            }
        }, 30000);
    }

    private void performServiceSearch() {
        String query = queryEditText.getText().toString().trim();
        if (query.isEmpty()) {
            Toast.makeText(this, "Inserisci un termine di ricerca", Toast.LENGTH_SHORT).show();
            return;
        }

        statusTextView.setText("Ricerca in corso (Service)...");
        resultsTextView.setText("");

        NewPipeSearchAPI.searchWithService(this, query, 
                NewPipeSearchAPI.SERVICE_YOUTUBE, 10,
                new NewPipeSearchAPI.SearchCallback() {
                    @Override
                    public void onSuccess(List<NewPipeSearchAPI.VideoResult> results) {
                        runOnUiThread(() -> {
                            statusTextView.setText("Ricerca completata: " + results.size() + " risultati");
                            displayResults(results);
                        });
                    }

                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> {
                            statusTextView.setText("Errore nella ricerca");
                            resultsTextView.setText("Errore: " + error);
                        });
                    }
                });
    }

    private void handleBroadcastResponse(Intent intent) {
        if (!isWaitingForResponse) return;
        
        isWaitingForResponse = false;
        
        try {
            unregisterReceiver(responseReceiver);
        } catch (Exception e) {
            // Receiver già non registrato
        }

        boolean success = intent.getBooleanExtra(SearchReceiver.EXTRA_SUCCESS, false);
        if (success) {
            String resultsJson = intent.getStringExtra(SearchReceiver.EXTRA_RESULTS);
            List<NewPipeSearchAPI.VideoResult> results = parseResultsJson(resultsJson);
            
            statusTextView.setText("Ricerca completata: " + results.size() + " risultati");
            displayResults(results);
        } else {
            String error = intent.getStringExtra(SearchReceiver.EXTRA_ERROR);
            statusTextView.setText("Errore nella ricerca");
            resultsTextView.setText("Errore: " + (error != null ? error : "Unknown error"));
        }
    }

    private void displayResults(List<NewPipeSearchAPI.VideoResult> results) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            NewPipeSearchAPI.VideoResult result = results.get(i);
            sb.append(i + 1).append(". ").append(result.title).append("\n");
            sb.append("   URL: ").append(result.url).append("\n");
            sb.append("   Canale: ").append(result.uploaderName).append("\n");
            if (result.duration > 0) {
                sb.append("   Durata: ").append(formatDuration(result.duration)).append("\n");
            }
            sb.append("\n");
        }
        
        resultsTextView.setText(sb.toString());
    }

    private List<NewPipeSearchAPI.VideoResult> parseResultsJson(String json) {
        try {
            JSONArray jsonArray = new JSONArray(json);
            List<NewPipeSearchAPI.VideoResult> results = new java.util.ArrayList<>();
            
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject jsonObject = jsonArray.getJSONObject(i);
                NewPipeSearchAPI.VideoResult result = new NewPipeSearchAPI.VideoResult();
                
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
        } catch (JSONException e) {
            Log.e(TAG, "Errore nel parsing JSON", e);
            return new java.util.ArrayList<>();
        }
    }

    private String formatDuration(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        
        if (hours > 0) {
            return String.format("%02d:%02d:%02d", hours, minutes, secs);
        } else {
            return String.format("%02d:%02d", minutes, secs);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        if (responseReceiver != null) {
            try {
                unregisterReceiver(responseReceiver);
            } catch (Exception e) {
                // Receiver già non registrato
            }
        }
    }
}

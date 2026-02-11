// Esempio: Come usare NewPipe API da un'altra app
public class NewPipeApiClient {
    
    public void searchVideos(Context context, String query) {
        // 1. Invia richiesta di ricerca
        Intent searchIntent = new Intent("org.schabi.newpipe.api.SEARCH");
        searchIntent.putExtra("query", query);
        searchIntent.putExtra("max_results", 5);
        sendBroadcast(context, searchIntent);
    }
    
    // 2. Registra receiver per risultati
    private void registerResultReceiver(Context context) {
        IntentFilter filter = new IntentFilter("org.schabi.newpipe.api.SEARCH_RESPONSE");
        context.registerReceiver(resultReceiver, filter);
    }
    
    private BroadcastReceiver resultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                String resultsJson = intent.getStringExtra("results");
                JSONArray results = new JSONArray(resultsJson);
                
                for (int i = 0; i < results.length(); i++) {
                    JSONObject video = results.getJSONObject(i);
                    
                    String title = video.getString("title");
                    String videoUrl = video.getString("url");
                    
                    // Estrai stream audio
                    JSONArray audioStreams = video.getJSONArray("audioStreams");
                    for (int j = 0; j < audioStreams.length(); j++) {
                        JSONObject audio = audioStreams.getJSONObject(j);
                        String audioUrl = audio.getString("url");
                        int bitrate = audio.getInt("bitrate");
                        
                        // Usa lo stream audio nel tuo player
                        playAudioStream(audioUrl, title);
                    }
                    
                    // Estrai DASH per ExoPlayer
                    String dashUrl = video.optString("dashManifestUrl", "");
                    if (!dashUrl.isEmpty()) {
                        playWithExoPlayer(dashUrl);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    };
    
    private void playAudioStream(String url, String title) {
        // Implementa riproduzione audio nel tuo player
    }
    
    private void playWithExoPlayer(String dashUrl) {
        // Implementa riproduzione DASH con ExoPlayer
    }
}

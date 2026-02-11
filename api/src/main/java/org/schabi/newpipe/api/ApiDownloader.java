package org.schabi.newpipe.api;

import androidx.annotation.NonNull;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;

import java.io.IOException;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

public final class ApiDownloader extends Downloader {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0";
    private static final String YOUTUBE_DOMAIN = "youtube.com";

    private final OkHttpClient client;

    public ApiDownloader() {
        this.client = new OkHttpClient.Builder()
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public Response execute(@NonNull final Request request) throws IOException {
        final String httpMethod = request.httpMethod();
        final String url = request.url();
        final Map<String, List<String>> headers = request.headers();
        final byte[] dataToSend = request.dataToSend();

        RequestBody requestBody = null;
        if (dataToSend != null) {
            requestBody = RequestBody.create(dataToSend);
        }

        final okhttp3.Request.Builder requestBuilder = new okhttp3.Request.Builder()
                .method(httpMethod, requestBody)
                .url(url)
                .addHeader("User-Agent", USER_AGENT);

        requestBuilder.addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        requestBuilder.addHeader("Accept-Language", Locale.getDefault().toLanguageTag());

        if (url.contains(YOUTUBE_DOMAIN)) {
            requestBuilder.addHeader("Cookie", "CONSENT=YES+1; SOCS=CAI");
        }

        headers.forEach((headerName, headerValueList) -> {
            requestBuilder.removeHeader(headerName);
            headerValueList.forEach(headerValue -> requestBuilder.addHeader(headerName, headerValue));
        });

        try (okhttp3.Response response = client.newCall(requestBuilder.build()).execute()) {
            String responseBodyToReturn = null;
            try (ResponseBody body = response.body()) {
                if (body != null) {
                    responseBodyToReturn = body.string();
                }
            }

            final String latestUrl = response.request().url().toString();
            return new Response(
                    response.code(),
                    response.message(),
                    response.headers().toMultimap(),
                    responseBodyToReturn,
                    latestUrl);
        }
    }
}

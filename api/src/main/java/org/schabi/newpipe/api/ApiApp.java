package org.schabi.newpipe.api;

import android.app.Application;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;

import java.util.Locale;

public class ApiApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        final Downloader downloader = new ApiDownloader();
        final Localization localization = Localization.fromLocale(Locale.getDefault());
        final ContentCountry contentCountry = new ContentCountry(Locale.getDefault().getCountry());
        NewPipe.init(downloader, localization, contentCountry);
    }
}

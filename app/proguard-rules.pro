# Methods called from page JavaScript through addJavascriptInterface.
-keepclassmembers class app.tvcouchbrowser.PageBridge {
    @android.webkit.JavascriptInterface <methods>;
}

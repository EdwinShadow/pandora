package tech.linjiang.pandora.network;

import android.content.ContentValues;

import java.io.IOException;
import java.nio.charset.Charset;

import okhttp3.Connection;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.internal.http.HttpHeaders;
import okio.Buffer;
import okio.BufferedSource;
import okio.GzipSource;
import okio.Okio;
import okio.Source;
import tech.linjiang.pandora.util.Config;
import tech.linjiang.pandora.util.JsonUtil;
import tech.linjiang.pandora.util.Utils;

/**
 * Created by linjiang on 2018/6/20.
 */

public class OkHttpInterceptor implements Interceptor {

    private static final String TAG = "OkHttpInterceptor";

    private NetStateListener listener;

    @Override
    public Response intercept(Chain chain) throws IOException {
        Connection connection = chain.connection();
        if (connection != null) {
            throw new IllegalStateException(
                    "should use addInterceptor instead of addNetworkInterceptor");
        }

        long id = -1;

        Request request = chain.request();
        if (Config.isNetLogEnable()) {
            id = insert(request);
            notifyStart(id);
        }

        Response response;
        try {
            response = chain.proceed(request);
        } catch (IOException e) {
            if (Config.isNetLogEnable() && id >= 0) {
                markFailed(id);
                notifyEnd(id);
            }
            throw e;
        }

        if (Config.isNetLogEnable() && id >= 0) {
            updateSummary(id, response);
            updateContent(id, response);
            notifyEnd(id);
        }
        return response;
    }

    private long insert(Request request) {
        ContentValues values = new ContentValues();
        ContentValues contentValues = new ContentValues();

        values.put(CacheDbHelper.SummaryEntry.COLUMN_NAME_STATUS, CacheDbHelper.SummaryEntry.Status.REQUESTING);
        values.put(CacheDbHelper.SummaryEntry.COLUMN_NAME_URL, request.url().encodedPath());
        values.put(CacheDbHelper.SummaryEntry.COLUMN_NAME_HOST, request.url().host() + ":" + request.url().port());
        values.put(CacheDbHelper.SummaryEntry.COLUMN_NAME_METHOD, request.method());
        values.put(CacheDbHelper.SummaryEntry.COLUMN_NAME_SSL, request.isHttps());
        values.put(CacheDbHelper.SummaryEntry.COLUMN_NAME_TIME_START, System.currentTimeMillis());
        values.put(CacheDbHelper.SummaryEntry.COLUMN_NAME_HEADER_REQUEST, JsonUtil.formatHeaders(request.headers()));

        RequestBody requestBody = request.body();
        if (requestBody != null) {
            try {
                values.put(CacheDbHelper.SummaryEntry.COLUMN_NAME_SIZE_REQUEST, requestBody.contentLength());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        boolean canRecognize = checkContentEncoding(request.header("Content-Encoding"));
        if (canRecognize) {
            contentValues.put(CacheDbHelper.ContentEntry.COLUMN_NAME_REQUEST, requestBodyAsStr(request));
        }
        long id = CacheDbHelper.SummaryEntry.insert(values);
        contentValues.put(CacheDbHelper.ContentEntry.COLUMN_NAME_SUMMARY_ID, id);
        CacheDbHelper.ContentEntry.insert(contentValues);
        return id;
    }

    private void updateSummary(long reqId, Response response) {
        ContentValues values = new ContentValues();
        values.put(CacheDbHelper.SummaryEntry.COLUMN_NAME_STATUS, CacheDbHelper.SummaryEntry.Status.COMPLETE);
        values.put(CacheDbHelper.SummaryEntry.COLUMN_NAME_TIME_END, System.currentTimeMillis());
        values.put(CacheDbHelper.SummaryEntry.COLUMN_NAME_CODE, response.code());
        values.put(CacheDbHelper.SummaryEntry.COLUMN_NAME_PROTOCOL, response.protocol().toString());
        values.put(CacheDbHelper.SummaryEntry.COLUMN_NAME_HEADER_RESPONSE, JsonUtil.formatHeaders(response.headers()));

        ResponseBody body = response.body();
        if (body != null) {
            MediaType type = body.contentType();
            if (type != null) {
                values.put(CacheDbHelper.SummaryEntry.COLUMN_NAME_CONTENT_TYPE_RESPONSE, type.toString());
            }
            values.put(CacheDbHelper.SummaryEntry.COLUMN_NAME_SIZE_RESPONSE, body.contentLength());
        }
        CacheDbHelper.SummaryEntry.update(reqId, values);
    }

    private void updateContent(long reqId, Response response) {
        boolean canRecognize = checkContentEncoding(response.header("Content-Encoding"));
        if (canRecognize) {
            ContentValues values = new ContentValues();
            values.put(CacheDbHelper.ContentEntry.COLUMN_NAME_RESPONSE, responseBodyAsStr(response));
            CacheDbHelper.ContentEntry.update(reqId, values);
        }
    }

    private void markFailed(long id) {
        ContentValues values = new ContentValues();
        values.put(CacheDbHelper.SummaryEntry.COLUMN_NAME_STATUS, CacheDbHelper.SummaryEntry.Status.ERROR);
        values.put(CacheDbHelper.SummaryEntry.COLUMN_NAME_TIME_END, System.currentTimeMillis());
        CacheDbHelper.SummaryEntry.update(id, values);
    }

    private void notifyStart(final long id) {
        if (listener != null) {
            Utils.post(new Runnable() {
                @Override
                public void run() {
                    listener.onRequestStart(id);
                }
            });
        }
    }

    private void notifyEnd(final long id) {
        if (listener != null) {
            Utils.post(new Runnable() {
                @Override
                public void run() {
                    listener.onRequestEnd(id);
                }
            });
        }
    }


    private boolean checkContentEncoding(String contentEncoding) {
        return contentEncoding == null ||
                contentEncoding.equalsIgnoreCase("identity") ||
                contentEncoding.equalsIgnoreCase("gzip");
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static String requestBodyAsStr(Request request) {
        RequestBody requestBody = request.body();
        if (requestBody == null) {
            return null;
        }
        String contentEncoding = request.header("Content-Encoding");
        boolean gzip = "gzip".equalsIgnoreCase(contentEncoding);
        Buffer buffer = new Buffer();
        try {
            requestBody.writeTo(buffer);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
        return sourceToStrInternal(buffer, gzip, requestBody.contentType());
    }

    /**
     * we need handle 'Content-Encoding' if 'Accept-Encoding' added by user instead of OKHttp
     *
     * @param response
     * @return
     */
    private static String responseBodyAsStr(Response response) {
        ResponseBody responseBody = response.body();
        if (responseBody == null || !HttpHeaders.hasBody(response)) {
            return null;
        }
        String contentEncoding = response.header("Content-Encoding");
        boolean gzip = "gzip".equalsIgnoreCase(contentEncoding);
        try {
            return sourceToStrInternal(
                    response.peekBody(Long.MAX_VALUE).source(), gzip, responseBody.contentType());
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private static String sourceToStrInternal(Source source, boolean gzip, MediaType contentType) {
        BufferedSource bufferedSource;
        if (gzip) {
            GzipSource gzipSource = new GzipSource(source);
            bufferedSource = Okio.buffer(gzipSource);
        } else {
            bufferedSource = Okio.buffer(source);
        }
        String tempStr = null;
        Charset charset = UTF8;
        if (contentType != null) {
            charset = contentType.charset(UTF8);
        }
        try {
            tempStr = bufferedSource.readString(charset);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                bufferedSource.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return tempStr;
    }

    public void setListener(NetStateListener listener) {
        this.listener = listener;
    }

    public void removeListener() {
        listener = null;
    }
}

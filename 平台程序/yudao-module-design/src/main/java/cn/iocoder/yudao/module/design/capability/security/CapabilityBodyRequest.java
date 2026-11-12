package cn.iocoder.yudao.module.design.capability.security;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Replays only the bounded, authenticated bytes; later generic wrappers cannot rewrite the wire body. */
final class CapabilityBodyRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    CapabilityBodyRequest(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream input = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public int read() {
                return input.read();
            }

            @Override
            public int read(byte[] bytes, int offset, int length) {
                return input.read(bytes, offset, length);
            }

            @Override
            public boolean isFinished() {
                return input.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new IllegalStateException("Capability endpoints are synchronous");
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }

    @Override
    public int getContentLength() {
        return body.length;
    }

    @Override
    public long getContentLengthLong() {
        return body.length;
    }

    @Override
    public String getCharacterEncoding() {
        return StandardCharsets.UTF_8.name();
    }

    @Override
    public boolean isAsyncSupported() {
        return false;
    }

    @Override
    public AsyncContext startAsync() {
        throw new IllegalStateException("Capability endpoints are synchronous");
    }

    @Override
    public AsyncContext startAsync(ServletRequest request, ServletResponse response) {
        throw new IllegalStateException("Capability endpoints are synchronous");
    }
}

package de.chrisliebaer.salvage.entity;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

/**
 * Callback handler for consuming log frames. Allows to perform blocking operations on the started execution.
 */
public class FrameCallback implements ResultCallback<Frame> {
	
	private final Consumer<Frame> consumer;
	private final CountDownLatch countDownLatch = new CountDownLatch(1);
	
	private Closeable closeable;
	private volatile Throwable error;
	
	public FrameCallback(Consumer<Frame> consumer) {
		this.consumer = consumer;
	}
	
	@Override
	public void onStart(Closeable closeable) {
		this.closeable = closeable;
	}
	
	@Override
	public void onNext(Frame frame) {
		consumer.accept(frame);
	}
	
	@Override
	public void onError(Throwable throwable) {
		error = throwable;
		countDownLatch.countDown();
	}
	
	@Override
	public void onComplete() {
		countDownLatch.countDown();
	}
	
	@Override
	public void close() throws IOException {
		closeable.close();
	}
	
	public void join() throws Throwable {
		countDownLatch.await();
		
		if (error != null) {
			throw error;
		}
	}
}

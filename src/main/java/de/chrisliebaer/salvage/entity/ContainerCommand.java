package de.chrisliebaer.salvage.entity;

import com.github.dockerjava.api.DockerClient;
import lombok.extern.log4j.Log4j2;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Represents command which is supposed to be executed in target container.
 */
@Log4j2
public record ContainerCommand(String command, String user) {
	
	public long run(DockerClient client, SalvageContainer container) throws Throwable {
		// check container config to see how to run command
		var dockerContainer = client.inspectContainerCmd(container.id()).exec();
		var config = dockerContainer.getConfig();
		
		// docker requires us to first create the exec
		var execBuilder = client.execCreateCmd(container.id())
				.withAttachStdout(true)
				.withAttachStderr(true)
				.withEnv(Arrays.asList(config.getEnv()))
				.withUser(user)
				.withPrivileged(dockerContainer.getHostConfig().getPrivileged())
				.withCmd(command);
		
		var exec = execBuilder.exec();
		
		// and then run it
		var callback = client.execStartCmd(exec.getId())
				.withDetach(false) // always stay attached so we get to know when process exits
				.exec(new FrameCallback(frame -> {
					var line = new String(frame.getPayload(), StandardCharsets.UTF_8).trim();
					log.trace("[exec] {}", line);
				}));
		callback.join();
		
		// check exit code
		var execInspect = client.inspectExecCmd(exec.getId()).exec();
		var exitCode = execInspect.getExitCodeLong();
		if (exitCode == null) {
			throw new IllegalStateException("execution of command in container " + container.id() + " failed");
		}
		return exitCode;
	}
}

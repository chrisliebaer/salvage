# Work in progress!

Salvage is currently being used in production, and no major issues have been reported except for those listed in the open issues section.
Please note that some configurations may not have been fully tested yet and there might be undiscovered bugs that could potentially cause catastrophic failures.

It's worth noting that Salvage does not issue any volume deletion commands and will mount all backup volumes as read-only, so data loss is extremly unlikely.

Additionally, there are currently no other crane implementations available other than the one used for the author's own infrastructure, which can be found in the "Crane" section below.

<p align="center">
	<img src="https://raw.githubusercontent.com/chrisliebaer/salvage/master/logo.png" width="50%" height="50%" alt="salvage logo"/>
</p>


Salvage is my solution for backing up small scale Docker applications.
Or more specifically: **my** Docker infrastructure.
As with most things Docker related, the puns are important.
So I anchored on `salvage`.
If you are interested in using salvage or how to use it, weigh anchor and set sail for the next section!

# Overview

Salvage is designed to identify all volumes on the current Docker instance that need to be backed up.
It accomplishes this by first identifying all containers attached to each volume and then performing their configured backup action to prepare the volume for backup, ensuring data consistency.

While salvage takes care of orchestrating all backups, it does not actually back up any data itself.
Instead, it delegates this job to `cranes`.
A crane is a Docker image that implements the salvage crane interface.
After salvage has prepared all attached containers for backup, it will instantiate a crane to perform the actual backup.
Salvage will wait for the crane to complete the backup before reversing the performed backup actions on all containers, returning everything back to full operation.

Backups are done in `tides`.
A tide configures when it will run.
The tide also decides which crane will be used by default.
All containers assigend to a specific tide, will be backed up, when this tide is executed.

This design allows you to back up volumes with most file-based backup solutions while ensuring that these solutions don't need to interface directly with Docker.
So, let salvage be your first mate in ensuring the safety of your Docker applications!

# Configuration

Salvage is supposed to retrieve individual backup definitions by checking for certain labels on each volume but requires a global configuration to configure the backup infrastructure itself.

## Daemon configuration

The daemon configuration is divided into environment variables and labels directly attached to the Salvage container.
While this might seem odd at first, it is done to maintain consistency with how other containers and volumes are configured via labels.

The following environment variables are used to configure the daemon:

* `MACHINE`: Name that will be passed to cranes to identify the current machine. This can be used to differentiate between different machines on the same storage.

Additionally, you must set the following label on the Salvage container for it to find itself: salvage.root.

### Tide configuration

A tide is a schedule that specifies a set of volumes to be backed up at the same time.
By hooking volumes into a tide, they will be backed up according to the tide's schedule.
Each tide can also specify a grouping strategy that determines how much downtime is expected for the backup.

The following labels are used to configure a tide, and they need to be present on the Salvage container:

* `salvage.tides.<name>.cron`: Cron expression specifying when the tide should be executed.
* `salvage.tides.<name>.grouping`: The grouping strategy to use for this tide. Possible values are:
	* `individual`: Each volume is backed up individually, and each dependent container is shut down and restarted before each volume is backed up.
	* `smart`: Strongly connected components are grouped together. Effectively all volumes and containers that can somehow be reached from each other are grouped together.
	* `project`: Same as `smart`, but containers inside the same compose project are always grouped together. This is because it doesn't make sense to shut down individual parts of an application, as they are not operational without other services.
* `salvage.tides.<name>.crane`: Default crane to use for this tide. Can be overridden by individual volumes.
* `salvage.tides.<name>.maxConcurrent`: The maximum number of concurrent backups for this tide. Might be lower if there are not enough volumes per group, as groups are run in sequence. It is also limited by the number of allowed Crane instances.

### Crane configuration

A crane is an image that implements the salvage crane interface.
The only cranes currently available are the ones that I'm using for myself.
But since salvage does most of the heavy lifting, writing a crane for your backup software of choice should be simple.

Check [salvage-cranes](https://github.com/chrisliebaer/salvage-crane) for available cranes.

The following labels are used to configure a crane and need to present on the salvage container:

* `salvage.cranes.<name>.image`: The image of this crane.
* `salvage.cranes.<name>.pullOnRun`: Whether to pull the image before running the crane, regardless of whether it is already present on the Docker daemon. Defaults to false.
* `salvage.cranes.<name>.env.<key>`: Additional environment variables to pass to the crane. For example `salvage.cranes.<name>.env.S3_BUCKET=my-bucket`.
* `salvage.cranes.<name>.mount.<volume>`: Mounts a volume to the crane. The volume will be mounted at the specified path. For exmaple `salvage.cranes.<name>.mount.my-volume=/cache`.

A few notes on crane volumes:

* Crane volumes are resolved on a global level, so you need to reference the volume by its name on the docker daemon, not the name of the volume in the `volume` section of the compose file.

* When no backup is in progress, crane containers will not exist on the docker daemon.
  As a result, volumes usually attached to crane containers will appear as orphaned and might be deleted depending on how you run your docker host and what other processes interact with it.
  To prevent this, you can attach the volume to the salvage container itself.
  In order to establish a stable mount point that will never clash with any future updates, please mount the volume at `/mnt/dummy/`.

An example excerpt of a salvage container with a crane volume attached might look like this:

```yaml
version: '3.9'
services:
  salvage:
    image: "ghcr.io/chrisliebaer/salvage:master"
    environment:
      - "MACHINE=my-machine"
      # [ ... ]
    labels:
      - "salvage.root=true"
      - "salvage.cranes.s3.image=..."
      - "salvage.cranes.s3.env.S3_SECRET=..."
      - "salvage.cranes.s3.env.S3_BUCKET=my-bucket"
      # Note that the global volume name is used here
      - "salvage.cranes.s3.mount.salvage-s3-cache=/cache"
      # [ ... ]
    volumes:
      - "/var/run/docker.sock:/var/run/docker.sock"
      # Mount the volume to salvage to prevent it from being orphaned. Salvage will never use this mount.
      - "s3-cache:/mnt/dummy/s3-cache:ro"
      # [ ... ]

volumes:
  s3-cache:
    external: true
    name: salvage-s3-cache
  # [ ... ]
```

### Volume configuration

By default, salvage will ignore all volumes it hasn't been explicitly instructed to back up.
In order to configure a volume for backup, you need to label it as such.
Since changing labels on volumes is not supported by Docker, volumes are configured by attaching labels to containers instead (see FAQ).
To do this, place the `salvage.tide.<name>=<volume1>,<volume2>,...` label on any container (it doesn't need to actually use the volume).
This might seem a bit odd, especially since the volume is not actually used by the container, but the process of discovering volumes is decoupled from the backup process that controls which containers need to be stopped or paused during backup.
Although you could attach the label to any container, it is recommended that you attach it to the container that actually uses the volume, for clarity and maintainability.

Salvage offers two ways of resolving the volume name:

* `compose project scope`: The volume name is the same as the name of the volume in the compose project (resolved via docker compose labels on volume).
* `global scope`: If volume name is prefixed with `g:` it will be resolved by the given name.

This also allows you to back up volumes that are not part of any compose project by attaching the `salvage.tide.<name>=g:<volume>` label to the salvage container itself.
salvage does not currently support backing up host files that may be used by services via bind mounts.
This use case may be added in the future.

### Container configuration

The following labels can be used on containers and will define how salvage will tell the container to stop modifying the backup volume.
All modifications to a container's state will be reverted after the backup is done.
Certain actions can only be performed on a container if the container is in a certain state.

* `salvage.action`: Defines if the container state should be altered before backing up its volumes. Possible values are:
	* `ignore`: Container state will not be altered. (Default if either pre- or post-action is set).
	* `stop`: (Default if no pre- or post-action is set) The container will be stopped before the backup is performed. (Ignored if container is already stopped.)
	* `pause`: The container will be paused before the backup is performed. (Ignored if container is already paused or stopped.)
* `salvage.command.pre` and `salvage.command.post`: Commands that will be executed before and after the backup within the container, similar to `docker exec`. Will not be executed if the container is stopped or paused.
* `salvage.command.exitcode`: Defines how different exit codes should be handled. Possible values are:
	* `ignore`: The exit code will be ignored.
	* `stop`: The backup will not be performed. (Default)
	* `custom`: Special handlingg. Instead of using the `custom` value, you are expected to provide a comma-separated list of exit codes that should be handled as `stop`. You can define ranges or single exit codes. For example `1,3-5,7-9`.
* `salvage.user`: User that will be used to execute the backup command. (Default is container's user)

# Salvage crane interface

Cranes are implemented using Docker images.
Salvage will pull the crane image, create a container from it, and prepare the backup environment.

The following environment variables are passed to the crane:

* `SALVAGE_MACHINE_NAME`: The name of the machine that is backing up, as specified in the daemon configuration.
* `SALVAGE_CRANE_NAME`: The name of the crane that is performing the backup.
* `SALVAGE_VOLUME_NAME`: The name of the volume.
* as well as any additional environment variables specified in the crane configuration.

Salvage will mount the volume at `/salvage/volume` (read-only) and include some metadata at `/salvage/meta`.
The crane is expected to back up and restore both of these directories.
**All cranes are required to contain the `/salvage/volume` and `/salvage/meta` directories in their image as otherwise volume access will fail in certain SELinux environments.**
The content within the `/salvage/meta` directory is not part of the crane interface, and may change at any time.
Do not rely on it.
Any additional volumes will be mounted writable, as specified in the crane configuration.

# Reporting and monitoring

Salvage can be configured to call Discord webhooks on certain events.
Configuration is done on a per-tide basis.
The following labels can be used to configure Discord webhooks for a tide:

* `salvage.tides.<name>.report.tide.success`: Called after a tide has been executed successfully.
* `salvage.tides.<name>.report.tide.failure`: Called after a tide has failed, may provide affected volumes, if the docker daemon was reachable.
* `salvage.tides.<name>.report.volume.success`: Called after a volume has been backed up successfully.
* `salvage.tides.<name>.report.volume.failure`: Called after a volume backup has failed.
* `salvage.tides.<name>.report.method`: The method to use for reporting. Can be `POST` or `GET`. Using `GET` will not deliver any payload. (Default is `POST`)

# Troubleshooting

Salvage will write logs to stdout using log4j2.
If you are familiar with log4j2, you can override its configuration (see log4j2 documentation).
Otherwise, you can set the `VERBOSE` environment variable to `true` to enable verbose logging.

# Restrictions

While salvage can attempt to minimize potential issues during backups, there are several factors outside its control that can impact the backup process.
For example, if a container is stopped or restarted while a backup is in progress, the data being backed up may be incomplete or corrupted.
In addition, if the Docker daemon itself goes down or the host system reboots unexpectedly, the backup may be interrupted or incomplete.

# FAQ

<details>
	<summary>Why isn't it recommended to back up the volume directory from outside of containers?</summary>

Although it may seem like a viable option, backing up the volume directory from outside of containers lacks the ability to stop the container from making changes to the volume during the backup process.
It necessitates the execution of an additional process on the host, which is not ideal in a Docker-only environment.
Maintaining container dependencies and volume configurations in two locations is required with this approach, whereas salvage enables configuration of everything in the services `docker-compose.yml` file.
</details>


<details>
	<summary>Can I run multiple salvage instances on the same docker daemon?</summary>

Running multiple salvage instances on the same Docker daemon is not supported.
salvage assumes that it is the only instance running on the Docker daemon, and if multiple instances are run, they can potentially modify the same backup volumes or interfere with each other's actions.
</details>

<details>
	<summary>Why not use volume labels to configure backup volumes?</summary>

Docker does not allow modifications to volume labels.
This means in order to (re)configure a volume for backup, you need to remove the volume and recreate it, which also means losing all data that is currently stored in the volume.
Backup configuration should not require risky volume operations.
</details>


<details>
	<summary>Can I attach a volume to multiple Tides?</summary>

You can, but I hope you know what you are doing and have checked if the crane supports this as well.
</details>

<details>
	<summary>Error when running crane: '/salvage/volume/': Permission denied</summary>

This is a known issue with empty volumes.
Before mounting a volume to a container, Docker will copy the content of the mount point into the volume.
This behavior ignores the `read-only` flag and can be disabled by setting the `nocopy` option, which is exactly what salvage does.
However, if the volume is empty, the mounted path will not be accessible by the container, making the backup fail.
The workaround is to create a dummy file in the volume before running salvage.

You can run the following command to create a dummy file in the volume:

```bash
docker run --rm -v VOLUMENAME:/mnt alpine touch /mnt/.salvage_workaround
```
</details>

# Contributing

Salvage is designed to work with external crane images, and contributions in the form of new crane images are always welcome.

If you wish to contribute to salvage itself, please open an issue first to discuss the feature you wish to add or the bug you want to fix.
I build salvage to solve a very specific problem and would like to keep the scope small.

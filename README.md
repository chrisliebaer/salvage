# WARNING: Work in progress! Very unstable, not ready for anything larger than a calculator.
Also, there aren't really any crane implementations yet, other than the one I use for my own infrastructure.

![Salvage Logo](https://raw.githubusercontent.com/chrisliebaer/salvage/master/logo.png)

salvage is my solution for backing up small scale Docker applications.
Or more specifically: **my** Docker infrastructure.
As with most things Docker related, the puns are important.
So I anchored on `salvage`.
Because we are trying to prevent losing our cargo ships.
I could go on for miles, but let's get back on course.

# Overview

salvage will identify all volumes on the current Docker instance, that need to be backed up.
It will then identify all containers attached to each volume and perform their configured *backup action* to prepare the volume for backup, ensuring data consistency.
If multiple containers are sharing a common set of volumes, and you would like to back them up all at the same time, `Tides` can be configured.
A Tide tells salvage which volumes should be backup up at the same time.
All affected containers of a Tide will have their *backup action* performed at the same time, to reduce potential downtime.

While salvage takes care of orchestrating all backups, it does not actually back up any data.
It delegates this job to *Cranes*.
A Crane is a Docker image, which implements the salvage Crane interface.
After salvage has prepared all attached containers for backup, it instances a Crane to perform the actual backup.
salvage will wait for the Crane to complete the backup, before reversing the performed *backup actions* on all containers, returning everything back to full operations.

This design allows to back up volumes with most file-based backup solutions, while ensuring that these solution don't need to interface with docker directly.

# Configuration

salvage is supposed to retrieve individual backup definitions by checking for certain labels on each volume but requires a global configuration to configure the backup infrastructure itself.

## Daemon configuration

The daemon configuration is divided into environment variables and labels directly attached to the salvage container.
This might seem odd at first, but since the configuration of other containers and volumes is done via labels, configuring the same things on salvage via environment variables would feel very strange.

The following environment variables are used to configure the daemon:

* `MACHINE`: Name that will be passed to cranes to identify the current machine. This can be used to differentiate between different machines on the same storage.

Additionaly, the following label must be set on the salvage container in order for it to find itself: `salvage.root`.

### Tide configuration

A Tide specifies a schedule for a set of volumes to be backed up at the same time.
Volumes can hook into a Tide and will then be backed up when the Tide is scheduled.
Each tide can specify a strategy for how to group individual volumes and their using containers to change how much downtime is expected.

The following labels are used to configure a tide and need to present on the salvage container:

* `salvage.tides.<name>.cron`: Cron expression specifying when the tide should be executed.
* `salvage.tides.<name>.grouping`: The grouping strategy to use for this tide. Possible values are:
    * `individual`: Each volume is backed up individually and ech dependent container is shut down and restarted before each volume is backed up.
    * `smart`: Strongly connected components are grouped together. Effectively all volumes and containers that can somehow be reached from each other are grouped together.
    * `project`: Same as `smart`, but containers inside the same compose project are always grouped together. The reason behind this is that it doesn't make sense to shut down individual parts of an application, as they are not operational with other services missing.
* `salvage.tides.<name>.crane`: Default crane to use for this tide. Can be overridden by individual volumes.
* `salvage.tides.<name>.maxConcurrent`: The maximum number of concurrent backups for this tide. Might be lower if there are not enough volumes per group, as groups are run in sequence. Also limited by the number of allowed crane instances.

### Crane configuration

A crane is an image that implements the salvage Crane interface.

The following labels are used to configure a crane and need to present on the salvage container:

* `salvage.cranes.<name>.image`: The image of this crane.
* `salvage.cranes.<name>.env.<key>`: Environment variables to pass to the crane. For example `salvage.cranes.<name>.env.S3_BUCKET=my-bucket`.
* `salvage.cranes.<name>.mount.<volume>`: Mounts a volume to the crane. The volume will be mounted at the specified path. For exmaple `salvage.cranes.<name>.mount.my-volume=/cache`.
 
**Note:** Crane volumes are resolved on a global level, so you need to reference the volume by its name on the docker daemon, not the name of the volume in the `volume` section of the compose file.

**Note:** While no backup is in progress, no crane containers will exist on the docker daemon.
This means volumes usually attached to crane containers, are not attached to anything and thus will appear as orphaned.
Depending on how you run your docker host and what other processes interact with it, someone might delete these volumes.
To prevent this, you can attach the volume to salve itself.
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

By default, Salvage will ignore all volumes it hasn't been explicitly instructed to back up.
In order to configure a volume for backup, you need to label it as such.
Since changing labels on volumes is not supported by Docker, volumes are configured by attaching labels to containers instead (see FAQ).
To do this, place the `salvage.tide.<name>=<volume1>,<volume2>,...` label on any container (it doesn't need to actually use the volume).
This might seem a bit odd, especially since the volume is not actually used by the container, but the process of discovering volumes is decoupled from backup process that controls which containers need to be stopped of paused during backup.
So while you could in theory attach the label to any container, it is recommended to attach it to the container that actually uses the volume.
Just for the sake of clarity and maintainability.

Salvage offers two ways of resolving the volume name:

* `compose project scope`: The volume name is the same as the name of the volume in the compose project (resolved via docker compose labels on volume).
* `global scope`: If volume name is prefixed with `g:` it will be resolved by the given name.

This also allows you to back up volumes that are not part of any compose project by attaching the `salvage.tide.<name>=g:<volume>` label to the salvage container itself.
What it does not allow you to do right now is also backing up host files that might be used by services via bind mounts.
This use case is not supported right now, but might be added in the future.

### Container configuration

The following labels can be used on containers and will define how Salvage will tell the container to stop modifying the backup volume.
All modifications to a container's state will be reverted after the backup is done.
Certain actions can only be performed on a container if the container is in a certain state.

* `salvage.action`: Defines if the container state should be altered before backing up its volumes. Possible values are:
    * `ignore`: Container state will not be altered. (Default if either pre- or post-action is set).
    * `stop`: (Default if no pre- or post-action is set) The container will be stopped before the backup is performed. (Ignored if container is already stopped.)
    * `pause`: The container will be paused before the backup is performed. (Ignored if container is already paused or stopped.)
* `salvage.command.pre` and `salvage.command.post`: Commands that will be executed before and after the backup within the container, similar to `docker exec`. Will not be executed if the container is stopped or paused.
* `salvage.user`: User that will be used to execute the backup command. (Default is container's user)

# salvage Crane Interface

Cranes are implemented via docker images.
salvage will pull the crane image, create a container from it and preparing the backup environment.
The following environment variables are passed to the crane:

* `SALVAGE_MACHINE_NAME`: The name of the machine that is backing up, as specified in the daemon configuration.
* `SALVAGE_CRANE_NAME`: The name of the crane that is performing the backup.
* `SALVAGE_VOLUME_NAME`: The name of the volume.
* as well as any additional environment variables specified in the crane configuration.

salvage will mount the volume at `/salvage/volume` (read-only) and include some metadata at `/salvage/meta`.
The crane is expected to back up and restore both of these directories.
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

Due to the nature of Docker, Salvage can not protect itself from outside events, such as stop requests, system reboots or other processes messing with a container state while a backup is running.
Salvage attempts to minimize potential issues, but ultimately requires the host to simply cooperate.

# FAQ

## Why not backup the volume directory from outside of containers?

If you think that's a good idea go ahead and do it.
Backing up the volume directory from outside of containers provides no means of stopping the container from modifying the volume while the backup is running.
It also requires you to run a separate process on the host, which is not what we want in a pure Docker setup.
Last but not least, you have to maintain container dependencies and volume configurations at two places, whereas with Salvage you can configure everything in the services `docker-compose.yml` file.

## Can I run multiple salvage instances on the same docker daemon?

No! salvage assumes to be the only instance running on a docker daemon.
If you run multiple instances, they will start to interfere with each other.

## Why not use volume labels to configure backup volumes?

Docker does not allow to modify volume labels.
This means in order to (re)configure a volume for backup, you need to remove the volume and recreate it, which also means losing all data that is currently stored in the volume.
Backup configuration should not require risky volume operations.

## Can I attach a volume to multiple Tides?

This sounds like a horrible idea, but it is possible.

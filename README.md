# jedis-test

Testing how Jedis pool behaves when working with redis cluster when master is stopped.

## Setup

- Build images etc

```bash
bb init
```

- Start the Redis and Redis sentinel instances. And the dev container too.

```bash
bb up
```

- Start vscode and run `Dev Containers: Reopen in container` command.
- Start calva by `Calva: Start a project and Connect`
- Open and eval the [jedistest.system](./src/main/jedistest/system.clj) namespace
- The experiment is a set of forms starting commented forms from line 111

The test is to first see that the replication works, see which instance is master and which is slave, then stop the master container, ensure that the slave is promoted to master, and see if the pool gives client connected to new master.

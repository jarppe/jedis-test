FROM clojure:temurin-23-tools-deps-bookworm-slim

WORKDIR /workspace

# Common base tools:

RUN \
  apt update -y                                                                    && \
  apt install -y                                                                   \
    lsb-release                                                                    \
    binutils                                                                       \
    procps                                                                         \
    gnupg                                                                          \
    git                                                                            \
    curl                                                                           \
    wget                                                                           \
    unzip                                                                          \
    python3                                                                        \
    jq                                                                             \
    inetutils-ping                                                                 \
    socat                                                                          \
    httpie                                                                         \
    mtr                                                                            \
    net-tools                                                                      \
    tcptraceroute

# Babashka

RUN \
  RELEASE=$(curl -sSLf "https://api.github.com/repos/babashka/babashka/releases/latest" | jq -r ".tag_name[1:]") && \
  case $(uname -m) in                                                              \
    aarch64) ARCH=aarch64;;                                                        \
    x86_64)  ARCH=amd64;;                                                          \
    *) echo "Unknown CPU: $(uname -m)"; exit 1;;                                   \
  esac                                                                             && \
  BB_BASE="https://github.com/babashka/babashka/releases/download"                 && \
  BB_TAR="babashka-${RELEASE}-linux-${ARCH}-static.tar.gz"                         && \
  curl -sSL "${BB_BASE}/v${RELEASE}/${BB_TAR}"                                     \
    | tar xzCf /usr/local/bin -                                                    && \
  bb --version

# Redis-cli

RUN \
  curl -fsSL https://packages.redis.io/gpg                                         \
    | gpg --dearmor -o /usr/share/keyrings/redis-archive-keyring.gpg               && \
  chmod 644 /usr/share/keyrings/redis-archive-keyring.gpg                          && \
  echo "deb [signed-by=/usr/share/keyrings/redis-archive-keyring.gpg]"             \
       "https://packages.redis.io/deb"                                             \
       $(lsb_release -cs)                                                          \
       "main"                                                                      \
    > /etc/apt/sources.list.d/redis.list                                           && \
apt update -y                                                                      && \
apt install -y --no-install-recommends                                             \
  redis-tools

# Misc tools:

RUN \
  apt install -qy --no-install-recommends                                          \
    rlwrap                                                                         \
    groff                                                                          \
    fzf

# Deps:

COPY deps.edn /workspace/deps.edn
RUN \
  clojure -A:cider -P

# Bash

COPY bashrc /root/.bashrc
CMD ["/bin/bash"]

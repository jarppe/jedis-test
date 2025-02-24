FROM debian:12-slim

ARG TARGETARCH
ENV LANG=C.UTF-8

#
# Common base tools:
#

RUN \
  apt update -y                                                                    && \
  apt install -y                                                                   \
    ca-certificates                                                                \
    apt-transport-https                                                            \
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
    tcptraceroute                                                                  && \
  apt upgrade -y


#
# Java:
#

ARG JAVA_VERSION=23

RUN \
  curl -sSLf https://packages.adoptium.net/artifactory/api/gpg/key/public          \
    | gpg --dearmor                                                                \
    > /etc/apt/trusted.gpg.d/temurin.gpg                                           && \
  echo "deb https://packages.adoptium.net/artifactory/deb"                         \
            $(lsb_release -cs)                                                     \
            "main"                                                                 \
    > /etc/apt/sources.list.d/adoptium.list                                        && \
  apt update -q                                                                    && \
  apt install -y                                                                   \
    temurin-${JAVA_VERSION}-jdk                                                    && \
  java --version

#
# Clojure:
#

RUN \
  RELEASE=$(curl -sSLf "https://api.github.com/repos/clojure/brew-install/releases/latest" | jq -r ".tag_name") && \
  curl -sSLf https://github.com/clojure/brew-install/releases/download/${RELEASE}/posix-install.sh \
    | bash -                                                                       && \
  clojure -P                                                                       && \
  clojure --version

#
# Babashka
#

RUN \
  RELEASE=$(curl -sSLf "https://api.github.com/repos/babashka/babashka/releases/latest" | jq -r ".tag_name[1:]") && \
  case ${TARGETARCH} in                                                            \
    arm64) BB_ARCH=aarch64;;                                                       \
    *)     BB_ARCH=amd64;;                                                         \
  esac                                                                             && \
  BB_BASE="https://github.com/babashka/babashka/releases/download"                 && \
  BB_TAR="babashka-${RELEASE}-linux-${BB_ARCH}-static.tar.gz"                      && \
  curl -sSL "${BB_BASE}/v${RELEASE}/${BB_TAR}"                                     \
    | tar xzCf /usr/local/bin -                                                    && \
  bb --version

#
# Redis-cli
#

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

#
# Docker CLI
#

RUN \
  curl -fsSL https://download.docker.com/linux/debian/gpg \
    -o /etc/apt/keyrings/docker.asc && \
  chmod a+r /etc/apt/keyrings/docker.asc && \
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc]" \
       "https://download.docker.com/linux/debian"                                  \
       "$(lsb_release -cs)"                                                        \
       "stable"                                                                    \
    > /etc/apt/sources.list.d/docker.list                                          && \
  apt update -q                                                                    && \
  apt install -qy                                                                  \
    docker-ce-cli

#
# kubectl
#

RUN \
  RELEASE=$(curl -sSLf https://dl.k8s.io/release/stable.txt)                       && \
  curl -sSLf "https://dl.k8s.io/release/${RELEASE}/bin/linux/${TARGETARCH}/kubectl" \
       -o /usr/local/bin/kubectl                                                   && \
  chmod 0755 /usr/local/bin/kubectl

#
# Krew:
#

RUN \
  mkdir /tmp/krew                                                                  && \
  cd /tmp/krew                                                                     && \
  curl -sSfL "https://github.com/kubernetes-sigs/krew/releases/latest/download/krew-linux_${TARGETARCH}.tar.gz" \
    | tar xzf -                                                                    && \
  ./krew-linux_${TARGETARCH} install krew                                          && \
  cd /root                                                                         && \
  rm -fr /tmp/krew                                                                 && \
  PATH=$PATH:/root/.krew/bin                                                       && \
  kubectl krew

ENV PATH=$PATH:/root/.krew/bin

#
# kubectl plugins: ctx and ns
#

RUN \
  kubectl krew install ctx                                                         && \
  kubectl krew install ns

#
# K6:
#

RUN \
  RELEASE=$(curl -sSLf "https://api.github.com/repos/grafana/k6/releases/latest" | jq -r ".tag_name") && \
  curl -sSLf "https://github.com/grafana/k6/releases/download/${RELEASE}/k6-${RELEASE}-linux-${TARGETARCH}.tar.gz" \
    | tar -C /usr/local/bin --strip-components 1 -xzf -

#
# Misc tools:
#

RUN \
  apt install -qy --no-install-recommends                                          \
    openssh-client                                                                 \
    rlwrap                                                                         \
    groff                                                                          \
    file                                                                           \
    fzf

#
# tini:
#

ENV TINI_VERSION v0.19.0
RUN \
  curl -sSLf "https://github.com/krallin/tini/releases/download/${TINI_VERSION}/tini" > /tini && \
  chmod +x /tini

ENTRYPOINT ["/tini", "--"]

#
# Lazygit:
#

RUN \
  case ${TARGETARCH} in                                                           \
    amd64) ARCH=x86_64;;                                                          \
    *)     ARCH=${TARGETARCH};;                                                   \
  esac                                                                            && \
  RELEASE=$(curl -sSLf "https://api.github.com/repos/jesseduffield/lazygit/releases/latest" | jq -r ".tag_name[1:]") && \
  curl -sSLf "https://github.com/jesseduffield/lazygit/releases/download/v${RELEASE}/lazygit_${RELEASE}_Linux_${ARCH}.tar.gz" \
    | tar -C /usr/local/bin -xzf - lazygit

#
# Workspace:
#

COPY  dotfiles/*  /root/

WORKDIR /workspace
CMD ["/bin/bash"]

#
# Download dependencies into image to speed up dev startup:
#

COPY deps.edn /workspace/deps.edn
RUN \
  clojure -A:cider -P

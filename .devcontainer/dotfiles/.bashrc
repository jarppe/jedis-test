alias l='ls -F'
alias ll='ls -Fl'
alias lll='ls -Fla'
alias m=more
alias k=kubectl
alias d=docker
alias dc='docker compose'
export PS1="[\e[0;31m${KUBE_CONTEXT:-\?}\e[0m/\e[0;31m${KUBE_NAMESPACE:-\?}\e[0m] \e[0;32m\w\e[0m > "

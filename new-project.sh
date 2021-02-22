sha="$(git ls-remote https://github.com/jeffp42ker/biff.git HEAD | awk '{ print $1 }')"
deps="{:deps {github-jeffp42ker/biff {:git/url \"https://github.com/jeffp42ker/biff\" :sha \"$sha\"}}}"
clj -Sdeps "$deps" -m biff.project

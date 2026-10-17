# Shared by the AWS test's scripts (sourced, not run): where things are, and Terraform's outputs. Credentials come
# from the AWS CLI's own sign-in (aws login); the scripts never read or print them.
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
# The git-ignored data (the seed, Valhalla's tiles) and the results live in the main checkout, also from a worktree.
MAIN="$(cd "$(git -C "$ROOT" rev-parse --path-format=absolute --git-common-dir)/.." && pwd)"
export AWS_REGION="${AWS_REGION:-us-east-1}" AWS_PAGER=""
tf() { terraform -chdir="$HERE" "$@"; }
out() { tf output -raw "$1"; }

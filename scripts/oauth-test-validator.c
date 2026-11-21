/*
 * The OAuth validator the OAuth tests log in against, built by scripts/oauth-test-server.ps1 and by the CI job.
 *
 * A token here names the identity it was issued to. Every token is authorized, and the token itself is reported
 * as the authenticated identity, which PostgreSQL then compares with the role logged in to. So the token
 * "legatus" logs in as legatus and as no one else, and a login that goes through shows the token reached the
 * server as the provider gave it.
 */
#include "postgres.h"
#include "fmgr.h"
#include "libpq/oauth.h"

PG_MODULE_MAGIC;

static bool validate_token(const ValidatorModuleState *state, const char *token, const char *role,
						   ValidatorModuleResult *result);

static const OAuthValidatorCallbacks callbacks = {
	PG_OAUTH_VALIDATOR_MAGIC,
	.validate_cb = validate_token,
};

const OAuthValidatorCallbacks *
_PG_oauth_validator_module_init(void)
{
	return &callbacks;
}

static bool
validate_token(const ValidatorModuleState *state, const char *token, const char *role,
			   ValidatorModuleResult *result)
{
	result->authorized = true;
	result->authn_id = pstrdup(token);
	return true;
}

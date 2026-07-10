# Authentication & Authorization

A `mongreldb-server` daemon runs in one of three modes:

1. **Open** (default) - no auth required.
2. **Bearer token** (`--auth-token <TOKEN>`) - every request must carry an
   `Authorization: Bearer <TOKEN>` header.
3. **HTTP Basic** (`--auth-users`) - every request must carry an
   `Authorization: Basic <base64(user:pass)>` header.

The Clojure client supports all three through `connect` options. This guide
shows each mode and how to manage users and roles via SQL when the server is
in Basic mode.

---

## Bearer token mode

Start the daemon with a token:

```sh
mongreldb-server --auth-token s3cret-token
```

Connect with `:token`. The token is sent as `Authorization: Bearer ...` on
every request.

```clojure
(def db (mdb/connect {:url "http://127.0.0.1:8453" :token "s3cret-token"}))

(try
  (let [ok (mdb/health db)]
    (println "healthy:" ok))
  (catch dev.visorcraft.mongreldb.AuthException _
    (println "bad or missing token")
    (System/exit 1)))
```

A missing or wrong token surfaces as `AuthException` (HTTP 401/403).

### Where the token comes from

Hard-coding secrets in source is bad practice. Read it from the environment:

```clojure
(let [token (System/getenv "MONGRELDB_TOKEN")]
  (when (or (nil? token) (empty? token))
    (println "MONGRELDB_TOKEN not set")
    (System/exit 1))
  (def db (mdb/connect {:token token})))
```

## Basic auth mode

Start the daemon with a users file or inline users:

```sh
mongreldb-server --auth-users
```

Connect with `:username` / `:password`:

```clojure
(def db (mdb/connect {:url      "http://127.0.0.1:8453"
                      :username "admin"
                      :password "s3cret"}))
```

The client base64-encodes `username:password` and sets
`Authorization: Basic ...` on every request.

## Token takes precedence

If you supply both, `:token` wins and Basic credentials are ignored. This
lets you layer an override without branching:

```clojure
(def db (mdb/connect {:url      url
                      :username "fallback"
                      :password "user"
                      :token    "overrides-everything"}))
```

## Verifying what gets sent

The auth header is applied in the private `apply-auth` helper, called from
every request. For debugging, point the client at a local echo server or
watch the daemon logs. A quick check with a tiny socket server is the fastest
way to inspect the `Authorization` header without a real daemon.

## User and role management via SQL

When the daemon is in Basic auth mode, users and roles live in the catalog
and are managed with SQL. Run these statements through `mdb/sql`.

### Create a user

```clojure
(mdb/sql db "CREATE USER alice WITH PASSWORD 'hunter2'")
```

### Alter a user

Change a password:

```clojure
(mdb/sql db "ALTER USER alice WITH PASSWORD 'new-password'")
```

Grant the admin role:

```clojure
(mdb/sql db "ALTER USER alice ADMIN")
```

`ALTER USER ... ADMIN` is how you promote a user to full administrative
privileges (table creation/drop, compaction, user management). Use it
sparingly.

### Drop a user

```clojure
(mdb/sql db "DROP USER alice")
```

### Roles and grants

```clojure
(mdb/sql db "CREATE ROLE analyst")
(mdb/sql db "GRANT SELECT ON orders TO analyst")
(mdb/sql db "GRANT analyst TO alice")
(mdb/sql db "REVOKE SELECT ON orders FROM analyst")
(mdb/sql db "DROP ROLE analyst")
```

Exact grant syntax mirrors the server's SQL flavor; consult the server's SQL
reference for the full `GRANT`/`REVOKE` grammar available in your build.

## Common pitfalls

**Auth errors look like other errors without a specific catch.** A 401/403
throws `AuthException`; a 404 throws `NotFoundException`. Always discriminate
by class rather than string-matching `(.getMessage e)`.

**Forgetting to set auth in production.** A client built with
`(mdb/connect)` and no credentials sends no credentials. Against an
auth-enabled daemon, every call throws `AuthException`. Centralize client
construction so the auth option is never accidentally dropped.

**Sharing one client across threads is fine; sharing credentials across users
is not.** A client map is safe for concurrent use, but it carries one
identity. If you serve multiple authenticated users, build a client per user
(or per request) with that user's token.

**Token in version control.** Put secrets in the environment, a secret
manager, or a file outside the repo. Never commit a real token.

## Next steps

- [errors.md](errors.md) - `AuthException` and the rest of the error hierarchy
- [quickstart.md](quickstart.md) - the full end-to-end walkthrough

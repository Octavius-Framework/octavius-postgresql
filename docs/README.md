# Octavius Documentation

*A Roman library shelved its scrolls in armaria — presses set along the wall and numbered, so that a
catalogue could say which one a text stood in. A reader went to that press rather than searching the room.
What made them one library was the reader who often wanted more than one of them open at once.*

Three sets of guides, because there are three things here and they are used separately. The **driver** speaks
the PostgreSQL wire protocol and stands on its own, with nothing above it. The **client** sits on the driver
and is optional; its pages are written in terms the driver's pages define, and point back at them rather than
restating them. **Migrations** sits on the driver too, beside the client rather than under it — an application
may take either, both, or neither.

| Guides                             | For                                                                            |
|:-----------------------------------|:-------------------------------------------------------------------------------|
| [The driver](driver/README.md)     | Sessions, queries, transactions, the type system, COPY, Spring, and more       |
| [The client](client/README.md)     | Query builders, transaction plans, `dynamic_dto`, annotation scanning and more |
| [Migrations](migrations/README.md) | Versioned and repeatable migrations, the history table, and what stops a run   |

If you have not connected yet, the driver's [Quickstart](driver/quickstart.md) is where to start; nothing in
the client or in migrations is reachable before that.

## API Reference

For signatures, properties and enum values, see the generated KDoc — rebuilt on every push to `master` and
covering every published module:

- [API Reference](https://octavius-framework.github.io/octavius-postgresql/)

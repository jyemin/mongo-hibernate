/*
 * Copyright 2025-present MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb.hibernate.query.select;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.hibernate.internal.FeatureNotSupportedException;
import com.mongodb.hibernate.query.AbstractQueryIntegrationTests;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.hibernate.annotations.Struct;
import org.hibernate.testing.orm.junit.DomainModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for v1 translator's {@code $elemMatch} emission for HQL of shape
 * {@code WHERE EXISTS (FROM <entity>.<arrayPath> a WHERE <body>)}.
 */
@DomainModel(annotatedClasses = {ElemMatchIntegrationTests.ElemMatchCart.class})
class ElemMatchIntegrationTests extends AbstractQueryIntegrationTests {

    private static final List<ElemMatchCart> testingCarts = List.of(
            new ElemMatchCart(
                    1,
                    0,
                    new ElemMatchLineItem[] {new ElemMatchLineItem("WIDGET-1", 3), new ElemMatchLineItem("GIZMO-1", 1)}),
            new ElemMatchCart(
                    2,
                    0,
                    new ElemMatchLineItem[] {new ElemMatchLineItem("WIDGET-1", 0), new ElemMatchLineItem("BOLT-2", 10)}),
            new ElemMatchCart(3, 0, new ElemMatchLineItem[] {new ElemMatchLineItem("GIZMO-1", 5)}));

    @BeforeEach
    void beforeEach() {
        getSessionFactoryScope().inTransaction(session -> testingCarts.forEach(session::persist));
        getTestCommandListener().clear();
    }

    @Test
    void existsSinglePredicate() {
        assertSelectionQuery(
                "from ElemMatchCart c where exists (from c.lineItems li where li.sku = 'WIDGET-1') order by c.id",
                ElemMatchCart.class,
                """
                {
                  "aggregate": "elemmatch_carts",
                  "pipeline": [
                    {
                      "$match": {
                        "lineItems": {
                          "$elemMatch": {
                            "sku": {
                              "$eq": "WIDGET-1"
                            }
                          }
                        }
                      }
                    },
                    {
                      "$sort": {
                        "_id": 1
                      }
                    },
                    {
                      "$project": {
                        "_id": true,
                        "lineItems": true,
                        "minQty": true
                      }
                    }
                  ]
                }""",
                resultList -> {
                    var ids = new ArrayList<Integer>();
                    resultList.forEach(c -> ids.add(c.id));
                    assertThat(ids).containsExactly(1, 2);
                },
                Set.of("elemmatch_carts"));
    }

    @Test
    void existsAndBody() {
        assertSelectionQuery(
                "from ElemMatchCart c where exists (from c.lineItems li where li.sku = 'WIDGET-1' and li.qty > 0) order by c.id",
                ElemMatchCart.class,
                """
                {
                  "aggregate": "elemmatch_carts",
                  "pipeline": [
                    {
                      "$match": {
                        "lineItems": {
                          "$elemMatch": {
                            "$and": [
                              {
                                "sku": {
                                  "$eq": "WIDGET-1"
                                }
                              },
                              {
                                "qty": {
                                  "$gt": {"$numberInt": "0"}
                                }
                              }
                            ]
                          }
                        }
                      }
                    },
                    {
                      "$sort": {
                        "_id": 1
                      }
                    },
                    {
                      "$project": {
                        "_id": true,
                        "lineItems": true,
                        "minQty": true
                      }
                    }
                  ]
                }""",
                resultList -> {
                    var ids = new ArrayList<Integer>();
                    resultList.forEach(c -> ids.add(c.id));
                    assertThat(ids).containsExactly(1);
                },
                Set.of("elemmatch_carts"));
    }

    @Test
    void existsOrBody() {
        assertSelectionQuery(
                "from ElemMatchCart c where exists (from c.lineItems li where li.sku = 'WIDGET-1' or li.qty > 5) order by c.id",
                ElemMatchCart.class,
                """
                {
                  "aggregate": "elemmatch_carts",
                  "pipeline": [
                    {
                      "$match": {
                        "lineItems": {
                          "$elemMatch": {
                            "$or": [
                              {
                                "sku": {
                                  "$eq": "WIDGET-1"
                                }
                              },
                              {
                                "qty": {
                                  "$gt": {"$numberInt": "5"}
                                }
                              }
                            ]
                          }
                        }
                      }
                    },
                    {
                      "$sort": {
                        "_id": 1
                      }
                    },
                    {
                      "$project": {
                        "_id": true,
                        "lineItems": true,
                        "minQty": true
                      }
                    }
                  ]
                }""",
                resultList -> {
                    var ids = new ArrayList<Integer>();
                    resultList.forEach(c -> ids.add(c.id));
                    assertThat(ids).containsExactly(1, 2);
                },
                Set.of("elemmatch_carts"));
    }

    @Test
    void existsNotBody() {
        assertSelectionQuery(
                "from ElemMatchCart c where exists (from c.lineItems li where not (li.sku = 'WIDGET-1')) order by c.id",
                ElemMatchCart.class,
                """
                {
                  "aggregate": "elemmatch_carts",
                  "pipeline": [
                    {
                      "$match": {
                        "lineItems": {
                          "$elemMatch": {
                            "$nor": [
                              {
                                "sku": {
                                  "$eq": "WIDGET-1"
                                }
                              }
                            ]
                          }
                        }
                      }
                    },
                    {
                      "$sort": {
                        "_id": 1
                      }
                    },
                    {
                      "$project": {
                        "_id": true,
                        "lineItems": true,
                        "minQty": true
                      }
                    }
                  ]
                }""",
                resultList -> {
                    var ids = new ArrayList<Integer>();
                    resultList.forEach(c -> ids.add(c.id));
                    assertThat(ids).containsExactly(1, 2, 3);
                },
                Set.of("elemmatch_carts"));
    }

    @Test
    void notExistsBody() {
        assertSelectionQuery(
                "from ElemMatchCart c where not exists (from c.lineItems li where li.sku = 'WIDGET-1') order by c.id",
                ElemMatchCart.class,
                """
                {
                  "aggregate": "elemmatch_carts",
                  "pipeline": [
                    {
                      "$match": {
                        "$nor": [
                          {
                            "lineItems": {
                              "$elemMatch": {
                                "sku": {
                                  "$eq": "WIDGET-1"
                                }
                              }
                            }
                          }
                        ]
                      }
                    },
                    {
                      "$sort": {
                        "_id": 1
                      }
                    },
                    {
                      "$project": {
                        "_id": true,
                        "lineItems": true,
                        "minQty": true
                      }
                    }
                  ]
                }""",
                resultList -> {
                    var ids = new ArrayList<Integer>();
                    resultList.forEach(c -> ids.add(c.id));
                    assertThat(ids).containsExactly(3);
                },
                Set.of("elemmatch_carts"));
    }

    @Test
    void existsCorrelatedBodyThrowsFeatureNotSupported() {
        getSessionFactoryScope().inTransaction(session -> assertThatThrownBy(() -> session.createSelectionQuery(
                                "from ElemMatchCart c where exists (from c.lineItems li where li.qty > c.minQty)",
                                ElemMatchCart.class)
                        .getResultList())
                .isInstanceOf(FeatureNotSupportedException.class));
    }

    @Entity(name = "ElemMatchCart")
    @Table(name = "elemmatch_carts")
    static class ElemMatchCart {
        @Id
        int id;

        int minQty;

        ElemMatchLineItem[] lineItems;

        ElemMatchCart() {}

        ElemMatchCart(int id, int minQty, ElemMatchLineItem[] lineItems) {
            this.id = id;
            this.minQty = minQty;
            this.lineItems = lineItems;
        }
    }

    @Embeddable
    @Struct(name = "ElemMatchLineItem")
    static class ElemMatchLineItem {
        String sku;
        int qty;

        ElemMatchLineItem() {}

        ElemMatchLineItem(String sku, int qty) {
            this.sku = sku;
            this.qty = qty;
        }
    }
}

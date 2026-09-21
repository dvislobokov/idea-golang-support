// Package store keeps orders.
package store

import "errors"

// ErrEmpty is returned for an order without items.
var ErrEmpty = errors.New("store: empty order")

type (
	// Item is a line of an order.
	Item struct {
		Name     string `json:"name"`
		Price    int    `json:"price"`
		Quantity int    `json:"quantity"`
	}

	// Priced is anything with a price.
	Priced interface {
		Total() int
	}
)

// Order is a list of items in one currency.
type Order struct {
	Currency string
	items    []Item
}

func NewOrder(currency string) *Order {
	return &Order{Currency: currency}
}

func (o *Order) Add(item Item) {
	o.items = append(o.items, item)
}

// Total is the sum of price times quantity.
func (o *Order) Total() int {
	total := 0
	for _, item := range o.items {
		total += item.Price * item.Quantity
	}
	return total
}

func (o *Order) Validate() error {
	if len(o.items) == 0 {
		return ErrEmpty
	}
	return nil
}

// Discounted is a Priced thing with a discount.
type Discounted struct {
	Order   *Order
	Percent int
}

func (d Discounted) Total() int {
	return d.Order.Total() * (100 - d.Percent) / 100
}

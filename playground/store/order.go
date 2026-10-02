// Package store keeps orders.
package store

import "errors"

// ErrEmpty is returned for an order without items.
var ErrEmpty = errors.New("store: empty order")

type (
	// Item is a line of an order.
	Item struct {
		Name     string `json:"name" validate:""`
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
	Currency string `json:"currency" yaml:"currency" xml:"currency" db:"currency" mapstructure:"currency" toml:"currency"`
	items    []Item `db:"items" mapstructure:"items" toml:"items"`
}

// Flags is padded by the compiler: 24 bytes where 16 would do (Alt+Enter: Reorder fields; fieldalignment of golangci-lint says so too).
type Flags struct {
	Debug   bool
	Timeout int64
	Verbose bool
}

// NewOrder
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

	Open
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

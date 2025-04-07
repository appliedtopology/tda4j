package org.appliedtopology.tda4s

import language.experimental.modularity

trait Field:
  type Self
  def zero: Self
  def one: Self
  def add(x: Self, y: Self): Self
  def sub(x: Self, y: Self): Self
  def mul(x: Self, y: Self): Self
  def div(x: Self, y: Self): Self

  extension (x: Self)
    def +(y: Self): Self = add(x, y)
    def -(y: Self): Self = sub(x, y)
    def *(y: Self): Self = mul(x, y)
    def /(y: Self): Self = div(x, y)

object Field:
  def apply[F: Field]: (F is Field) = summon[F is Field]

  given (Double is Field) = new Field:
    type Self = Double
    def zero: Self = 0.0
    def one: Self = 1.0
    def add(x: Self, y: Self): Self = x + y
    def sub(x: Self, y: Self): Self = x - y
    def mul(x: Self, y: Self): Self = x * y
    def div(x: Self, y: Self): Self = x / y
    